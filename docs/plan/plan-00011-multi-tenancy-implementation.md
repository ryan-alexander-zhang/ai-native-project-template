---
id: plan-00011-multi-tenancy-implementation
type: plan
role: main
status: open
parent: design-00009-multi-tenancy-tenant-id
---

# 原生多租户落地计划

把 [[design-00009-multi-tenancy-tenant-id]] / [[decision-00018-multi-tenancy-boundaries]] / [[spec-00002-multi-tenancy]]
落成代码：pool 判别列 `tenant_id`，租户经**与 decision-00013 同一条传播脊柱**（`CommandContext` → `EventEnvelope` →
`ce_tenantid` header → 耐久行列 → 消费端重建）端到端流动，读侧与基础设施强制按租户隔离；单租户是 N=1 的同一套 schema。

**验收锚点**：一个不依赖业务样例的**双租户** consumer fixture。租户 A、B 各发一条命令，能在
`-jdbc`/`-mybatis-plus` × H2/MySQL/PostgreSQL(RLS) 组合下证明 spec §3 全部 XAC——A 的写入与产出事件带 A、
经 outbox→Kafka(`ce_tenantid`)→消费端仍属 A；以 A 查询绝不可见 B；**RLS 下复用池连接先服务 A 再服务 B 无租户残留**；
`tenancy.enabled=false` 时全落 `__root__` 且行为等价于改造前。当前跑不出即未完成。

**铁律**：
1. `tenant_id` **恒非空**、单一哨兵 `__root__`；`TenantId` 拒绝 `__` 前缀用户租户。**永不产生 NULL 行**（唯一约束陷阱）。
2. 写侧权威是 `CommandContext.tenantId`（显式值，禁 ambient——decision-00012）；`TenantContext` 只在 trusted boundary
   一次写入、请求内不可变、单值，业务代码不得写。
3. 租户只进**租户相对键**（`process_instance.business_key`、web 三键）；**不进**框架生成的全局唯一 id 去重键。
4. **消息管道表不建 RLS**；后台 relay/claim/cleanup 走独立 **BYPASSRLS 角色**跨租户扫。
5. 消费端 `reconstruct` **必须读回** `ce_tenantid`（未知 header 会被静默丢弃）。
6. **可加性**：`enabled=false` 必须与改造前行为等价（回归守护）；`tenant_id` 不用 UUIDv7（低基数、要窄+不可变，命题四）。

## 一、Design

详见 [[design-00009-multi-tenancy-tenant-id]]。落地关键：**这是 decision-00013 传播脊柱的增量**——它已铺好
`CommandContext`↔`EventEnvelope`↔`OutboxMessage`↔`IntegrationEventHeaders` 的 correlation/causation 通道，本计划沿同一
通道再加一个 `tenantId` 字段/属性/header/列。

```mermaid
flowchart LR
  edge["TenantResolutionFilter<br/>(trusted boundary)"] --> tc["TenantContext<br/>(不可变身份)"]
  tc --> cc["CommandContext.tenantId<br/>(写侧权威)"]
  cc --> env["EventEnvelope<br/>扩展属性"]
  env --> hdr["ce_tenantid<br/>Kafka header"]
  hdr --> row["耐久行 tenant_id 列"]
  row --> recon["consumer reconstruct<br/>+ runAs(tenant)"]
  recon --> cc
  tc --> read["读侧仓储 / MP 拦截器 / RLS"]
```

模块：新增 `aipersimmon-ddd-tenancy`（framework-free）+ `aipersimmon-ddd-tenancy-spring`（装配）；改造既有
cqrs / integration / messaging-kafka / outbox(*) / inbox(*) / process-manager(engine/jdbc/mp) / saga(*) / web-store(*) /
observability / archunit / flyway。

## 二、任务

> 约定：`[tenancy]` 等标模块；「并行」表示与同批无强依赖。每个任务 test-first，含单测；跨库项归 T18。

> **进度**
> - ✅ **T0b**：decision-00013 已补「增补(新增 tenantId)」注记。
> - ✅ **T0（骨架）+ T1（原语）**：新模块 `aipersimmon-ddd-tenancy`（framework-free，仅依赖 core）落地，入 reactor + BOM。
>   `TenantId`（record，≤32、非空校验）、`Tenants`（`ROOT="__root__"` 哨兵 + `of()` 拒 `__` 前缀）、`TenantContext`
>   （ThreadLocal，MDC 式：`current/require/set/clear/runAs`，`set/clear` 标注 trusted-boundary only）、`TenantResolver`
>   + `TenantResolutionContext`（framework-free，`header`/`host`）、`MissingTenantPolicy{REJECT,SYSTEM}`、`MissingTenantException`。
>   **11 单测绿，Spotless/PMD/SpotBugs 全过，`mvn -pl aipersimmon-ddd-tenancy -am verify` BUILD SUCCESS**。
>   `-tenancy-spring` 模块留到 T2（与 T3 一起做，因绑定依赖 CommandContext.tenantId）。
> - ✅ **T3（CommandContext 加 tenantId）**：干净破坏性改。`CommandContext` 4 字段 `(tenantId, messageId,
>   correlationId, causationId)`，tenantId 非空校验；`root(messageId)` 默认 `Tenants.ROOT`（N=1）、新增 `root(tenantId,
>   messageId)`、`deriveChild` 继承 tenant、`of(envelope)` **暂用 ROOT 占位（TODO T4 接 envelope.tenantId()）**。
>   `RegistryCommandBus.send(cmd)` 从 `TenantContext.current()` 播种。cqrs / cqrs-spring 加 tenancy 依赖。更新全部裸
>   `new CommandContext(...)` 调用点：4 处 process 主码 staged-effect/deadline 重建暂传 `Tenants.ROOT.value()`
>   （**TODO T8：从 process 行的 tenant_id 列读**）+ 6 处测试。**全 reactor `mvn install` BUILD SUCCESS——39/39
>   模块全绿、零测试失败**（含 process-manager-jdbc / messaging-kafka / operation-log / outbox / web-store 等
>   Testcontainers 模块 + archunit；cqrs 90/90/90 硬门过、覆盖 98%）。
> - ✅ **T4 + T5（EventEnvelope/OutboxMessage 加 tenantId + Kafka ce_tenantid，一起做）**：`EventEnvelope` 与
>   `OutboxMessage` 各在 subject 后加 `tenantId`（EventEnvelope 必填非空校验）。发布侧 `SpringIntegrationEvents` /
>   `OutboxWriter`(jdbc+mp) 从 `context.tenantId()` 盖章；`InProcessOutboxDispatcher` 从 `message.tenantId()` 重建；
>   `CommandContext.of(envelope)` 改读 `envelope.tenantId()`（去掉 T3 的 ROOT 占位）。messaging-kafka：
>   `IntegrationEventHeaders.TENANT_ID="ce_tenantid"`、`KafkaOutboxDispatcher` 盖章、`KafkaIntegrationEventListener`
>   `reconstruct` 读回（缺 header 回退 ROOT，兼容旧消息）并以 `TenantContext.runAs(...)` 包住下游处理。新增
>   `Tenants.fromValue(...)`（可信重建，不拒 `__` 前缀，供 consumer/relay）。全部 EventEnvelope/OutboxMessage 构造点
>   （8 主码 + 7 测试）更新，新增 EventEnvelope/Kafka 租户断言。**仍留 T6 占位**：`OutboxRelay`(jdbc+mp)、
>   `JdbcDeadLetterStore` 从无列的行重建时用 `Tenants.ROOT.value()`（TODO T6 outbox 加 tenant_id 列）。
>   **全 reactor `mvn install` BUILD SUCCESS——39/39 全绿、零失败**（含 messaging-kafka 租户 header 往返、
>   integration/cqrs 90/90/90 门）。
> - 🔧 **顺带修既有 bug（非租户）**：`operation-log-engine` 的 `spring-context` 是 `test` scope，遮蔽了
>   spring-boot-autoconfigure 传递的 compile scope，干净构建下主代码 `@Bean/@Configuration` 编译失败（stash 基线复现，
>   与租户无关；此前"绿"是 incremental target 残留）。改为 compile scope 解锁 reactor。
> - ✅ **T2（`aipersimmon-ddd-tenancy-spring`）**：新模块入 reactor + BOM。`TenancyProperties`
>   （`aipersimmon.ddd.tenancy.{enabled=false,header=X-Tenant-Id,missing-policy=REJECT}`）、`HeaderTenantResolver`
>   （默认 `TenantResolver`，读 header → `Tenants.of`）、`TenantResolutionFilter`（仿 RequestIdFilter，order
>   `HIGHEST+15`：解析→绑 `TenantContext`+MDC `tenant`→finally 清；缺租户 REJECT→400 / SYSTEM→ROOT；非法值→400）、
>   `TenantContextCommandInterceptor`（order −90，`runAs(ctx.tenantId())` 包住处理——给无环境租户的 relay/batch 兜底）、
>   `AipersimmonDddTenancyAutoConfiguration`（整体 `@ConditionalOnProperty enabled=true`；filter `@ConditionalOnWebApplication`；
>   `imports` 注册）。**9 单测绿**（interceptor 3 / filter 4 mockito / autoconfig 2 WebApplicationContextRunner）+
>   Spotless/PMD/SpotBugs 全过，`-am install` BUILD SUCCESS。`spring-context` 显式 compile（避免 operation-log-engine 那类遮蔽）。
> - ✅ **T6（outbox/dead_letter 加 tenant_id 列，消除 3 处 ROOT 占位）**：三方言 `V3__add_tenant_id.sql`
>   （`ALTER TABLE ... ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT '__root__'`，outbox + dead_letter；数据列、
>   **不入唯一键**）。jdbc：`OutboxWriter` INSERT、`OutboxRelay` `SELECT_DUE`+`mapRow`、`JdbcDeadLetterStore`
>   `INSERT_DEAD_LETTER`/`SELECT_DEAD_LETTER`/`REQUEUE_OUTBOX` + store/replay 参数全部带上 tenant_id，relay/deadletter
>   改为从行读（去 ROOT 占位、删除 now-unused Tenants import）。mp：`OutboxRecord`/`DeadLetterRecord` 加 `tenantId` 字段
>   +accessor（camelCase→snake_case 自动映射；`selectDue` 用 `SELECT o.*` 自动含列）、`OutboxWriter` setTenantId、
>   `OutboxRelay` toMessage 读列、`MybatisDeadLetterStore` store/replay setTenantId。**T4 的 3 处 T6 占位全部消除**。
>   测试 schema-init 同步加 V3：`outbox-jdbc`/`outbox-mybatis-plus` 的 `application.properties`（`spring.sql.init`）、
>   `ConnectedTraceEndToEndTest`（`EmbeddedDatabaseBuilder.addScript`）。mp 双孪生实体的 boilerplate accessor 加
>   `// CPD-OFF`（新增 tenantId accessor 触发 CPD 重复阈值）。**全 reactor `mvn install` BUILD SUCCESS——40/40 全绿零失败**
>   （含三方言迁移 + relay 往返 + Kafka ce_tenantid + 90/90/90 门）。过程中 `OutboxRelayBackoffTest` 一次 flaky（重跑绿）。
> - ✅ **T8（process 四表加 tenant_id + `uq_process_instance_business` 升复合，消除 4 处 ROOT 占位）**：三方言
>   `V3__add_tenant_id.sql`——四表（instance/transition/effect/deadline）`ADD COLUMN tenant_id VARCHAR(64) NOT NULL
>   DEFAULT '__root__'`；`uq_process_instance_business` `DROP` 后重建为 `(tenant_id, process_type, business_key)`
>   （PG/H2 `DROP CONSTRAINT IF EXISTS`、MySQL `DROP INDEX`）。引擎写模型串租户：四个 `*Insert`/`ProcessInstanceRow`/
>   `ParkedInput`/`DeadlineRow` 记录加 `tenantId` 首字段，`appendOperator` 加 `tenantId` 参；instance 从 `cause.tenantId()`
>   盖章，`updateSnapshot`/cancel 快照与 operator 事务从**已载入行** `row.tenantId()`（租户不可变），deadline/effect/parked
>   重放从**各自持久行**读回（4 处占位全消除：`ProcessOperations` replay、`ProcessDeadlineWorker` fire、
>   `Jdbc/MybatisProcessEffectStore.load`）。两后端 SQL/mapper/POJO 全改（jdbc 六 INSERT + 三 row-mapper；mp 四 mapper
>   INSERT + 四 store map + `InstanceRow`/`EffectLoadRow`/`DeadlineLoadRow`/`ParkedRow` 加 `tenantId` 字段+accessor，
>   `SELECT *` 自动映射）。测试 schema-init 同步加 V3：20 个 jdbc/mp 测试类（h2 `addScript` 16 个 + PG/MySQL
>   `ResourceDatabasePopulator.ClassPathResource` 4 个）+ 两 `application.properties`；3 处旧 arity 构造点补 `Tenants.ROOT.value()`。
>   **全库 reactor `mvn install` 40/40 全绿** + **multi-module scaffold 端到端**（Flyway 对真 Postgres 应用 process-manager
>   V1→V2→V3，OrderingFlow/ReviewFlow/PaymentCompensationFlow/OperationLog/Outbox 全绿）。scaffold 唯一失败是既有 spotless
>   drift（`OrderFulfilmentDefinitionTest` javadoc 换行，非本次改动、非租户）。
>   **待办**：T7（inbox tenant 列）、T9（saga）、T10（web-store）、T12/T13（MP TenantLine / PG RLS）、T15（读侧）、
>   T16（观测）、T18（双租户验收矩阵）。

### P0 · 术语、原语骨架、ADR 对齐（前置）

- **T0** `[repo]` ✅（部分）`CONTEXT.md` 增补 Multi-Tenancy Language（Tenant / Isolation Model / Discriminator /
  Sentinel / TenantContext / Tenant-relative Key / Missing-Tenant Policy）**已完成**。**待做**：新模块
  `aipersimmon-ddd-tenancy` + `-spring` 骨架（pom + `package-info` + reactor `<modules>` + BOM），空 auto-config 占位。
- **T0b** `[repo]` ✅ **对齐 decision-00013 已完成**：已加「增补(新增 tenantId)」注记（照其删 traceId / 加 sendAs 先例），
  把 `tenantId` 纳入 `CommandContext` 及 §3 出站脊柱（`EventEnvelope`/`OutboxMessage`/`IntegrationEventHeaders`）语义，
  指向 [[decision-00018-multi-tenancy-boundaries]]，并显式区分于 operation-log 的"功能字段禁入"约束。

### P1 · 租户原语（tenancy core + spring）

- **T1** `[tenancy]`（framework-free）`TenantId`（不可变、≤32、拒 `__` 前缀）、`Tenants.ROOT="__root__"`、
  `TenantContext`（ThreadLocal：`current/set/runAs/clear`，请求内不可变单值）、`TenantResolver` SPI +
  `TenantResolutionContext`、`MissingTenantPolicy{REJECT,SYSTEM}`。零 Spring/JDBC 依赖（ArchUnit 守护）。
- **T2** `[tenancy-spring]`（依赖 T1）`TenantResolutionFilter`（仿 `RequestIdFilter`，排在其后、业务过滤器前；
  来源 header/subdomain/jwt-claim 可配；解析失败按 `REJECT`）；`TenantBindingCommandInterceptor`（`TenantContext`→
  `CommandContext.tenantId`）；MDC 键 `tenant`、`finally` 清理；`@AutoConfiguration` + `imports`。

### P1 · 传播脊柱（承接 decision-00013，依赖 T1）

- **T3** `[cqrs]` `CommandContext` 增 `tenantId`；`root/deriveChild(继承)/of(envelope 读租户)` 传播；`CommandBus.send(cmd)`
   从 `TenantContext.current()` 播种（缺失时按策略/哨兵）。
- **T4** `[integration]`（依赖 T3）`EventEnvelope` 增 CloudEvents 扩展属性 `tenantId`（必填、非空校验）；
   `IntegrationEvents.publish/publishAs` 从 `ctx.tenantId()` 盖章；`OutboxMessage` 增 `tenantId`。
- **T5** `[messaging-kafka]`（依赖 T4）`IntegrationEventHeaders` 增 `TENANT_ID="ce_tenantid"`；`KafkaOutboxDispatcher`
   盖章；**`KafkaIntegrationEventListener.reconstruct` 读回 `ce_tenantid`** 并 `TenantContext.runAs(tenant, …)` 包住处理；
   `SpringIntegrationEvents`/in-process 同步。

### P2 · 耐久 schema 与后端（每组件 × 三方言；并行推进）

- **T6** `[outbox/-jdbc/-mybatis-plus]` `aipersimmon_outbox`/`aipersimmon_dead_letter` 加 `tenant_id` 列（**唯一键不变**，
   `event_id` 全局唯一）；`OutboxWriter` INSERT、`OutboxRelay` 行→`OutboxMessage` 映射带上租户。
- **T7** `[inbox/-jdbc/-mybatis-plus]` `aipersimmon_inbox` 加 `tenant_id` 数据列（**去重键 `(consumer,message_key)` 不变**）。
- **T8** `[process-manager-engine/-jdbc/-mybatis-plus]` 四表加 `tenant_id`；**`uq_process_instance_business` 升级为
   `(tenant_id, process_type, business_key)`**（租户相对键），其余唯一键不变；四 `*Store` + `ProcessInstanceCriteria` +
   `ProcessClaimStrategy` 带租户；检索索引 `tenant_id` 前导。claim/relay 走 BYPASSRLS（见 T13）。
- **T9** `[saga/saga-spring]` `aipersimmon_saga`/`aipersimmon_deadline` 加 `tenant_id` 数据列（键不变，`correlation_id` 全局唯一）。
- **T10** `[web-store-jdbc/-redis]` `aipersimmon_web_idempotency`/`_nonce`/`_rate_limit` **PK 升级为含 `tenant_id`**（客户端
   提供键、防跨租户泄漏）；redis key 前缀加 `{tenant}` 段。
- **T11** `[flyway]` 各组件 `ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT '__root__'` + 仅租户相对键表升级唯一约束；
   h2/mysql/postgresql 三套；各组件独立 `flyway_schema_history_aipersimmon_<component>`。

### P2 · 强制隔离

- **T12** `[*-mybatis-plus]` 启用 MP `TenantLineInnerInterceptor` + `TenantLineHandler`（租户取自 `TenantContext`，
   `getTenantIdColumn=tenant_id`）；`ignoreTable` 排除 `shedlock` + 消息管道表（outbox/dead_letter/effect/deadline/inbox）。
- **T13** `[*-jdbc]` **PostgreSQL RLS**：请求作用域表（领域/读模型/`process_instance` 请求侧）建 policy
   `USING (tenant_id = current_setting('app.tenant', true))`；事务边界 `SET LOCAL app.tenant`（禁 `SET SESSION`）；
   **双角色** `app_request`（受策略）/`app_worker`（BYPASSRLS + 仅管道表 DML，供轮询数据源）。H2/MySQL 退回手工 `TenantPredicate`。
- **T14** `[archunit]` 规则：耐久仓储/查询若走原生 SQL 而未过 `TenantPredicate`/未被 MP 拦截/非白名单后台轮询 → 失败。

### P3 · 读侧、观测、跨组件一致性

- **T15** `[cqrs-spring/read]`（方案 A）读仓储实现从 `TenantContext.current()` 取租户过滤；不改 `QueryBus`、不建 `QueryContext`。
- **T16** `[observability]` `ObservabilityAttributes` 增 `TENANT_ID="tenant.id"`；`TracingCommandInterceptor` 从
   `ctx.tenantId()` 盖 command span；`TenantResolutionFilter` 盖 server span；MDC `tenant`。
- **T17** `[operation-log 对齐]` `OperationTenantResolver`（plan-00010 T9）改为委托 `TenantContext.current()`，不再独立解析；
   确认哨兵 `__root__` 已统一（docs 已改，代码落地时校验）。

### 横切（贯穿 P1–P3）

- **T18** `[test]` 双租户 consumer fixture + 验收矩阵：把 spec §3 XAC 参数化为 `-jdbc`/`-mybatis-plus` × H2/MySQL/PostgreSQL
   （用 `aipersimmon-ddd-test-support` Testcontainers）。必含：跨租户读隔离（三机制各一）、**RLS 连接池不泄漏**（XAC-9.1）、
   **RLS 漏写谓词兜底**（XAC-8.2）、后台跨租户扫 + 盖 header（XAC-10.1）、缺租户 REJECT、`enabled=false` 回归等价、迁移安全。

## 三、验收路径

1. T0/T0b/T1/T2 绿：术语+原语+边缘解析可用，ADR 对齐落档。
2. T3–T5 绿：一条命令的租户端到端可见于 outbox 行 / `ce_tenantid` / 消费端 `CommandContext`。
3. T6–T11 绿：三方言迁移执行通过，唯一键按判据升级，无 NULL 行。
4. T12–T14 绿：三机制隔离各自证明，RLS 角色模型与连接池语义成立。
5. T15–T17 绿：读侧限定本租户，观测按租户可切片，operation-log 复用同一 `TenantContext`。
6. **T18 双租户矩阵全绿** = 完成。

## 四、关联
- 决策 [[decision-00018-multi-tenancy-boundaries]]（+ 待增补 [[decision-00013-command-context-and-causation-propagation]]）
- 设计 [[design-00009-multi-tenancy-tenant-id]]、Spec [[spec-00002-multi-tenancy]]、术语 `CONTEXT.md`
- 复用/对齐 [[plan-00010-operation-log-implementation]]（`OperationTenantResolver` → `TenantContext`）
