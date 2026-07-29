---
id: design-00009-multi-tenancy-tenant-id
type: design
role: main
status: active
parent: decision-00018-multi-tenancy-boundaries
---

# 多租户设计：`tenant_id` 判别列、租户原语组件与端到端传播

本文把"如何让 `aipersimmon-ddd` 原生支持多租户"落成**可实施的结构设计**。方案采用 **pool 模型
（共享 schema + `tenant_id` 判别列）**，配一个薄的 framework-free 租户原语组件，并把租户作为**旁路元数据**
织入现有写路径、消息与耐久基础设施。

设计遵循框架既有约定：租户是**元数据，永远随命令/信封旁路传播，绝不进入业务 payload**
（沿用 [[decision-00013-command-context-and-causation-propagation]]、[[decision-00014-cloudevents-integration-event-contract]]）；
新原语按 observability 的拆法分为 framework-free 内核 + Spring 装配
（沿用 [[design-00005-observability-and-distributed-tracing]]）。

本文是结构设计。§十三的取舍已由 [[decision-00018-multi-tenancy-boundaries]] 固化；本文提供机制细节，ADR 提供决策与取舍。
下一步产出 spec/plan、再编码。

---

## 一、结论

1. **隔离模型 = pool（判别列）**：所有基础设施表新增 `tenant_id`，而非分库/分 schema。理由：本框架的
   outbox relay、process-manager claim、saga deadline poll 全是**单一全局轮询器**；判别列让轮询器保持"扫全表、
   租户只是行上的数据"的单线程模型，而连接路由会逼后台线程改成"按租户逐个轮询 + 按租户命名锁"，与现架构对冲。
   **silo（分库/分 schema）作为 phase 2 可选后端**，且**现在零成本预留**（见 §八 seam）：silo 本质是一个读
   `TenantContext` 的路由 DataSource，透明垫在现有 `JdbcTemplate`/mapper 之下，pool→silo 是纯增量，`tenant_id`
   列在 silo 下只是无害冗余。现在不写路由代码，只守两条：`TenantContext` 是租户唯一环境权威；代码不假设单一物理库。

2. **`tenant_id` 恒非空**：`tenant_id VARCHAR(64) NOT NULL DEFAULT '__root__'`。哨兵取**保留令牌 `__root__`**（不用 `0`：
   真实租户 id 可能恰为 `"0"`，撞上即跨租户泄漏），并在 `TenantId` 校验里**禁止用户租户以 `__` 开头**，结构上杜绝碰撞。
   **单租户 = N=1 的多租户**：列与谓词永远存在；`aipersimmon.tenancy.enabled` 只切换"解析器从请求读租户 / 硬返回哨兵"，
   不改 schema、不改代码路径。**禁止用 NULL 表示"无租户"**（§六说明为什么这会静默摧毁唯一约束）。

3. **租户作为旁路元数据端到端传播**，与 trace 同款待遇：
   `边缘 Filter → TenantContext（ThreadLocal）→ CommandContext.tenantId → EventEnvelope 扩展属性
   → ce_tenantid Kafka header → 耐久行的 tenant_id 列 → 消费端 reconstruct 读回 → CommandContext.of(envelope)`。

4. **新增一个薄组件** `aipersimmon-ddd-tenancy`(framework-free) + `aipersimmon-ddd-tenancy-spring`(装配)：
   只放 `TenantId` / `TenantContext` / `TenantResolver` SPI / `MissingTenantPolicy` / 哨兵常量。
   真正的隔离靠改造既有组件——租户是横切标识，不像操作日志那样能自成一体。

5. **强制隔离分三层**：
   - MyBatis-Plus 后端 → 直接用 MP 原生 `TenantLineInnerInterceptor` 自动注入 `tenant_id` 谓词与 INSERT 列；
   - JDBC 后端（PostgreSQL）→ **RLS 为必需的数据库层防漏后盾**（不再是可选）；H2/MySQL 无真 RLS，退回手工谓词 + helper；
   - ArchUnit 规则兜底，禁止耐久仓储/查询漏掉租户维度。
   **注意 RLS 与全局轮询的边界**：RLS 只加在"请求作用域、租户已知"的表（领域表 / 读模型 / `process_instance` 检索读）；
   outbox/inbox/effect/deadline 等**消息管道表不加 RLS**，后台轮询连接用可 BYPASS RLS 的角色（见 §七、§八）。

6. **第一阶段范围**：租户原语 + 写路径核心（cqrs / integration）+ 耐久基础设施（outbox / inbox /
   process-manager / saga / web-store）+ 可观测性维度 + PostgreSQL RLS。跨库路由（silo）、按租户配额、租户级加密**不是** MVP。

---

## 二、租户原语组件（新增）

### 2.1 `aipersimmon-ddd-tenancy`（framework-free）

- `TenantId`：值对象（`Identifier` 家族），不可变、按值比较；非空、非空白、长度 ≤ 64。
- `Tenants`：哨兵与工厂。`Tenants.ROOT`（哨兵默认租户，字面量如 `__root__`，或固定 UUID）、`Tenants.of(String)`。
- `TenantContext`：**ThreadLocal 持有者**，类比 MDC。`current()` / `set(TenantId)` / `runAs(TenantId, Supplier)` / `clear()`。
  这是读侧仓储与基础设施实现读取环境租户的唯一入口。**不开放可变属性池、不做通用变量袋**（沿用
  [[decision-00012-no-ambient-per-command-state]] 的克制）。
  - **`effective()`：缺绑定决策的唯一收口点**（`issue-00099`）。凡是盖章或过滤 `tenant_id` 的基础设施都调用它，
    不再各自 `orElse(ROOT)`：有绑定用之；多租户关且无绑定 → `Tenants.ROOT`；多租户开且无绑定 →
    抛 `MissingTenantException`。**不提供**无条件抛出的 `require()`——与 `effective()` 同概念两个名字。
  - `isRequired()` / `setRequired(boolean)`：部署级模式旗标。旗标是部署事实而非请求事实，故为 `volatile` 静态，
    唯一合法的搬动方式是 `TenantEnforcement`。
- `TenantEnforcement`：框架无关的模式开关（`enable()` / `disable()`）。两个 tenancy auto-config 都以
  `@Bean(initMethod="enable", destroyMethod="disable")` 把它绑到上下文生命周期；
  `tenancy-mybatis-plus` 也注册是因为它不依赖 starter 却是真正改写 SQL 的模块，安全性不能取决于兄弟模块在不在，
  `@ConditionalOnMissingBean` 保证只建一个。destroy 降旗，避免同 JVM 内先后两个上下文互相继承模式。
- `MissingTenantException`：多租户开启且租户必需却缺失时抛出。两个边界会抛：边缘解析不出（`REJECT` 策略）、
  以及 `effective()` 在无绑定线程上被调用。**后者是调用方的 bug（绑定跨线程丢了或从未建立），不是坏输入，
  故不得映射成 4xx。**
- `TenantResolver`（SPI）：`Optional<TenantId> resolve(TenantResolutionContext)`。默认实现由 spring 模块提供，
  **须显式 opt-in**（§十一）。
- `MissingTenantPolicy`（SPI）：`REJECT`（多租户开启且解析失败 → 拒绝请求）/ `SYSTEM`（回退哨兵）。
  作用域只到**边缘**：它按"一个请求"决策，表达不了"基础设施层当前线程没有绑定"，后者归 `effective()`。

### 2.2 `aipersimmon-ddd-tenancy-spring`（装配）

- `TenantResolutionFilter extends OncePerRequestFilter`：**模仿 `RequestIdFilter`**，在过滤链最前段解析租户
  （可配置来源：header / 子域名 / JWT claim），`set` 进 `TenantContext`，`finally` 清理，并回写 MDC / span 维度。
  排在 `RequestIdFilter`(HIGHEST+10) 之后、业务过滤器之前。
- `TenantBindingCommandInterceptor implements CommandInterceptor`：把 `TenantContext.current()` 绑进
  `CommandContext.tenantId`（见 §三），并在异步/批处理/调度入口用 `TenantContext.runAs(...)` 显式设租户。

---

## 三、写路径核心传播

### 3.1 `CommandContext`（cqrs）新增 `tenantId`

```java
public record CommandContext(String tenantId, String messageId, String correlationId, String causationId) {
  // root/deriveChild 保持 tenantId 不变；of(EventEnvelope) 从信封租户扩展属性取
}
```

- `root(tenantId, messageId)`；`deriveChild(childMessageId)` 继承 `tenantId`；
  `of(EventEnvelope)` 读 `envelope.tenantId()`。
- `CommandHandler.handle(cmd, ctx)` 与 `CommandInterceptor.intercept(cmd, ctx, inv)` 已经拿到 ctx，无需改签名。
- 这是租户在 JVM 内的**唯一权威载体**；所有发布器与拦截器从这里读。

### 3.2 `EventEnvelope`（integration）新增租户扩展属性

- 新增组件 `String tenantId`（CloudEvents 扩展属性，**必填、非空**）。
- `IntegrationEvents.publish(event, CommandContext)` / `publishAs(...)` 从 `ctx.tenantId()` 盖章。

---

## 四、消息与 Kafka 传播

- `IntegrationEventHeaders` 新增 `TENANT_ID = "ce_tenantid"`。
- `OutboxMessage` record 新增 `tenantId` 字段。
- `KafkaOutboxDispatcher.dispatch` 用 `addHeader(TENANT_ID, message.tenantId())` 盖章。
- **`KafkaIntegrationEventListener.reconstruct` 必须读回 `ce_tenantid`**（否则未知 header 被静默丢弃——这是消费侧
  头号缺口），重建 `EventEnvelope.tenantId`，进而 `CommandContext.of(envelope)` 带上租户。
- 消费入口用 `TenantContext.runAs(tenantId, ...)` 包住处理逻辑，让下游读侧/仓储拿到环境租户。

---

## 五、耐久基础设施 schema 改造

**每张业务表新增列**：`tenant_id VARCHAR(64) NOT NULL DEFAULT '__root__'`；三套 vendor（h2/mysql/postgresql）
各出一条 Flyway 迁移（各组件独立 `flyway_schema_history_aipersimmon_<component>`）。

**唯一键判据（关键）**：`tenant_id` 只在**自然键是"租户相对"（跨租户可能合法重复）**时进入唯一约束；
**框架生成的全局唯一 id 不入键**（对应 owner 决策 4"租户不进 inbox/outbox 去重键"）。

| 表 | 唯一约束 | 租户是否入键 | 说明 |
|---|---|---|---|
| `aipersimmon_outbox` | `UNIQUE(event_id)`（不变） | 否 | `event_id` 全局唯一；`tenant_id` 仅数据列 |
| `aipersimmon_dead_letter` | `UNIQUE(event_id)`（不变） | 否 | 同上 |
| `aipersimmon_inbox` | `PK(consumer, message_key)`（不变） | 否 | `message_key=ce_id` 全局唯一 |
| `aipersimmon_process_instance` | `uq(tenant_id, process_type, business_key)` | **是** | `business_key` 是消费方自然键，跨租户会合法撞车 |
| `aipersimmon_process_transition` | `uq(instance_id, input_message_id)`（不变） | 否 | instance 已隐含租户；input id 全局唯一 |
| `aipersimmon_process_effect` | `uq(transition_id, effect_index)`（不变） | 否 | transition_id 全局唯一 |
| `aipersimmon_process_deadline` | `uq(instance_id, name, generation)`（不变） | 否 | instance 已隐含租户 |
| `aipersimmon_saga` | `PK(correlation_id)`（不变） | 否 | `correlation_id` 全局唯一 |
| `aipersimmon_deadline`(saga) | `PK(correlation_id, name)`（不变） | 否 | 同上 |
| `aipersimmon_web_idempotency` | `PK(tenant_id, idempotency_key)` | **是** | 客户端提供，跨租户撞键会**返回他人缓存响应=泄漏** |
| `aipersimmon_web_nonce` | `PK(tenant_id, nonce)` | **是** | 边缘提供，避免跨租户误判重放 |
| `aipersimmon_web_rate_limit` | `PK(tenant_id, bucket_key, window_start)` | **是** | 避免跨租户共享限流预算 |

- **所有表都加 `tenant_id` 数据列**（用于 header 盖章 / 观测 / RLS 过滤），但**是否入唯一键**按上表判据区分。
- `aipersimmon_process_instance` 的检索索引应以 `tenant_id` 为前导列（Salesforce 式），保证按租户扫描高效。
- `shedlock` 是 ShedLock 契约表（固定列名），**不加 `tenant_id`**；pool 模型下它只服务 outbox cleanup
  （relay 已改为每行租约、多实例并发轮询，见 §八与 [[issue-00108-a-killed-relay-instance-stops-all-delivery]]）。
- web-store-redis 无 DDL → key 前缀加租户段：`aipersimmon:web:{tenant}:idem:` 等。

存储端口（`ProcessInstanceStore` 等）与 `ProcessInstanceCriteria` 新增 `tenant` 维度；耐久行携带 `tenant_id`
的方式与 `traceparent/trace_state` **完全同款**（写行时落列、relay/claim 线程读回并盖到出站 header）。

### 5.1 `tenant_id` 值语义与性能

- **框架不生成 `tenant_id`**：它是从请求（header/JWT/子域名）解析进来的**外部不透明标识**，由消费方/租户注册中心分配。
- **不该用 UUIDv7 作 `tenant_id`**：UUIDv7 的收益是"时间有序→高频插入的 B-tree 局部性"，只对**高基数、高频插入**的
  每行 id 有意义；`tenant_id` 低基数、极少插入，时间有序**零收益**。它的性能杠杆是**窄 + 不可变 + 作复合索引前导列**：
  它乘在每行 × 每个含它的索引上，宽度是乘数级成本；被嵌进唯一键/索引/已投递的 `ce_tenantid`，**必须不可变**（禁止改名/重分配）。
- 约束：`TenantId` 值**不可变、有界长度（建议 ≤32）、不透明**。若租户注册中心选择用 UUID 作*格式*（理由是"免协调全局唯一"，
  非性能），应存原生 `uuid`/`BINARY(16)` 而非 36 字符文本，以免吃掉宽度惩罚——但框架不强制格式。
- **与 per-row id 正交**：框架当前每行 id（`event_id`/command messageId/effect id 等）用 `UUID.randomUUID()`（v4）。
  若要改善 outbox/inbox/process 表插入局部性，换 UUIDv7/ULID 是**另一条独立改进**，不属本设计范围
  （operation-log engine `DefaultOperationLogs` 已为其 `recordId` 预留可注入 time-ordered supplier，是同一思路的先例）。

---

## 六、为什么 `tenant_id` 必须 NOT NULL（唯一键/索引陷阱）

SQL 的唯一约束里 **NULL ≠ NULL**。PostgreSQL / MySQL(InnoDB) / H2 默认都把多个 NULL 视为互不相等：
含 `tenant_id` 的复合唯一键（如 `uq(tenant_id, process_type, business_key)`、`PK(tenant_id, idempotency_key)`）
一旦 `tenant_id IS NULL`，`(NULL, …)` 可重复出现 → **唯一约束被静默架空**：跨租户 `business_key` 撞车、
`Idempotency-Key` 返回他人缓存响应，且无任何报错。即便不入唯一键的列，查询侧 `WHERE tenant_id = ?` 与 RLS 策略也
都匹配不到 NULL 行。

因此：`tenant_id NOT NULL DEFAULT '__root__'`，**单租户即 N=1**。开关只控制解析行为，不产生 NULL 行。
（对比：.NET ABP 用可空 `TenantId` 表示 host 共享数据，因其实体不把租户放进唯一约束、且全局过滤器专门处理
`IS NULL`；我们的租户相对键（§五）有 `tenant_id` 入约束，必须 never-null。）

---

## 七、强制隔离（防漏）

1. **MyBatis-Plus 后端**：启用 MP `TenantLineInnerInterceptor` + `TenantLineHandler.getTenantId()`（从
   `TenantContext.current()` 取），自动给 SELECT/UPDATE/DELETE 注入 `tenant_id = ?` 并给 INSERT 补列；
   `ignoreTable()` 排除 `shedlock`。近乎零业务改动。
2. **JDBC 后端 — PostgreSQL 必须启用 RLS**（owner 决策 3）：`CREATE POLICY ... USING (tenant_id = current_setting('app.tenant'))`
   + 每事务 `SET LOCAL app.tenant=?`。即使某条 SQL 漏写谓词，DB 也拒绝跨租户（Salesforce/Citus 式）。
   **H2/MySQL 无真 RLS** → 退回手工谓词 + 统一 `TenantPredicate` helper。
   **RLS 作用域(重要)**：只对"请求作用域、租户已知"的表建策略——领域表 / 读模型 / `process_instance` 的请求侧检索。
   **消息管道表（outbox / dead_letter / process_effect / process_deadline / inbox）不建 RLS**：这些表由无租户上下文的
   后台轮询器**跨租户全表扫描**，RLS 会让轮询器瞎掉。后台 relay/claim/cleanup 使用**独立的 BYPASSRLS 角色**连接，
   并以"tenant 只是行数据、非隔离谓词"的方式工作（与决策 4 自洽）。
3. **ArchUnit**：新增规则，标记耐久仓储/查询里未经过 `TenantPredicate` / 未被 MP 拦截、又非白名单后台轮询的原生 SQL。

---

## 八、后台轮询与锁

pool 模型下，租户只是行上的数据：

- outbox relay / process claim / deadline poll **不按租户分片扫表**；每行的 `tenant_id` 在出站时盖到
  `ce_tenantid`。**无需按租户命名锁**——relay 与 process claim 都以行级租约互斥（无全局锁），
  cleanup 的 ShedLock 锁名也与租户无关。这些连接走 §七 的 BYPASSRLS 角色。
- 唯一注意：若将来要做**按租户公平调度 / 配额**，才需要按租户分片轮询 + 参数化锁名——列为 phase 2，不在 MVP。

**silo 扩展 seam（现在零成本预留，owner 决策 1）**：将来若某租户要物理隔离，新增一个读 `TenantContext.current()`
的 `TenantRoutingDataSource`（Spring `AbstractRoutingDataSource`）垫在 `JdbcTemplate`/mapper 之下即可，业务代码与
`tenant_id` 列都不需要改（列在 silo 下只是无害冗余）。**现在不写路由代码**，仅在 spring 装配里确保数据源是可被后续
替换的单一 bean，并守住"`TenantContext` 是唯一环境权威、代码不假设单一物理库"。

---

## 九、读侧 seam（真缺口）

现状：`QueryBus` 直接路由、无 `QueryContext`、无拦截器链、无读仓储基类 → 租户过滤无处强制。方案二选一：

- **(A) 环境租户**（推荐，低摩擦）：读仓储实现从 `TenantContext.current()` 取租户；MP 读侧同样吃
  `TenantLineInnerInterceptor`；JDBC 读侧走 `TenantPredicate` / RLS。无需改 `QueryBus`。
- **(B) 查询上下文**：给 `RegistryQueryBus` 加一条查询拦截器链并引入 `QueryContext(tenantId)`，镜像命令侧。
  更对称但改动大。

**owner 决策 5：取 (A)**；(B) 作为若将来需要查询侧审计/多维元数据时的演进。

---

## 十、可观测性

- `ObservabilityAttributes` 新增 `TENANT_ID = "tenant.id"`。
- `TracingCommandInterceptor` 从 `ctx.tenantId()` 盖到 command span；`TenantResolutionFilter` 盖到 server span。
- MDC 新增键 `tenant`（`TenantResolutionFilter` 设置、`finally` 清理），`ProblemDetailFactory` 可选带入。
- 效果：trace / 日志 / 指标可按租户切片。

---

## 十一、开关与兼容/迁移

- `aipersimmon.ddd.tenancy.enabled`（默认 `false`）：`false` → `TenantResolver` 恒返回 `Tenants.ROOT`，
  `MissingTenantPolicy` 不生效，`effective()` 在无绑定时回退哨兵；
  `true` → 从配置来源解析，解析失败按 `MissingTenantPolicy`，且 `effective()` 在无绑定时**抛异常**。
- **`MissingTenantPolicy` 默认 = `REJECT`**（owner 决策 6，安全默认）：多租户开启却解析不出租户时直接拒绝请求
  （400/401），绝不悄悄回退到共享桶；`SYSTEM`（回退哨兵）仅供受控内部/迁移场景显式选用。
- **`aipersimmon.ddd.tenancy.trust-header`（默认 `false`）**（`issue-00099`）：是否信任 `header` 指定的请求头。
  多租户开启 + 无自定义 `TenantResolver` + 未 opt-in → **拒绝启动**（`UntrustedTenantHeaderException` +
  FailureAnalyzer 给出两种安全接法）。理由见 [[decision-00018-multi-tenancy-boundaries]] 命题 13c。
- **跨线程传播**：`TenantContextTaskDecorator`（tenancy-spring 提供，`@ConditionalOnMissingBean(TaskDecorator)`）。
  Spring Boot 只在恰好一个 `TaskDecorator` bean 时应用它，故消费方自带 decorator 时本 bean 主动让位——
  否则会静默把对方的也一起废掉；这种情形下消费方须把租户传播组合进自己的 decorator。
  它**不覆盖**自建 executor。后台轮询器（relay / deadline worker / cleanup）本就无请求租户，
  这也是框架自有表刻意不进 `tenant-tables` 的原因——加进去会让每次轮询都失败。
- **`exclude-paths` 按容器派发路径匹配**（`issue-00099`）：原先用 `getRequestURI()` 原始值匹配，
  而容器会把 `/actuator/../orders` 规约成 `/orders` 才选 handler，于是穿越串能借用 `/actuator/**` 排除项
  跳过整个租户解析。现改为规约后匹配，且对 `;` 路径参数、`%2f`/`%5c`/`%2e` 编码、无法消解的前导 `../`
  一律视为"不排除"——宁可去解析并拒绝，也不跳过。
- **迁移安全**：既有单租户库执行 `ALTER TABLE ... ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT '__root__'`，
  存量行自动落哨兵；仅**租户相对键的表**（§五：`process_instance` / 三张 web-store）需把唯一约束升级为含 `tenant_id`
  的复合键（存量哨兵行不冲突），其余表只加列不改约束。全程无需停机改代码路径。

---

## 十二、组件适配清单（按传播顺序）

- 新增：`aipersimmon-ddd-tenancy`、`aipersimmon-ddd-tenancy-spring`。
- Tier1 写核心：`cqrs`(CommandContext)、`integration`(EventEnvelope)。
- Tier2 耐久：`outbox`/`-jdbc`/`-mybatis-plus`、`messaging-kafka`、`inbox`/`-jdbc`/`-mybatis-plus`、
  `process-manager-engine`/`-jdbc`/`-mybatis-plus`、`saga`/`saga-spring`、`web-store-jdbc`/`-redis`、`flyway`。
- Tier3 读侧：`cqrs`/`cqrs-spring`（取 §九 方案 A）。
- Tier4 观测：`observability`/`-otel`/`-otel-spring-boot-starter`。
- 兜底：`archunit`。

---

## 十三、取舍与 owner 决策（待 ADR 固化）

owner 已初步拍板如下；ADR 阶段确认后即固化。

1. **pool vs silo** → **pool**；silo 作为 phase 2 可选后端，**现在零成本预留** seam（§八）。
2. **哨兵字面量** → **`__root__`**（保留令牌，禁止用户租户以 `__` 开头；不用 `0`，因真实租户可能取 `"0"` 致碰撞泄漏）。
3. **JDBC 是否强制 RLS** → **PostgreSQL 必须启用 RLS**；H2/MySQL 退回手工谓词；消息管道表不建 RLS、后台走 BYPASSRLS 角色（§七）。
4. **租户是否进去重唯一键** → **否**（生成的全局唯一 id 不入键）；但**租户相对的自然键**（`business_key`、web `idempotency_key` 等）
   仍须入键，这是正确性/防泄漏所需（§五判据）。
5. **读侧 A vs B** → **A（环境租户）**。
6. **缺租户策略默认值** → **`REJECT`**（安全默认）。

ADR 阶段仍需确认的运维细节：RLS 的 `SET LOCAL app.tenant` 与连接池交互、后台 BYPASSRLS 角色的最小权限、
silo seam 的数据源 bean 形态。

---

## 十四、验收/测试矩阵（骨架）

- NULL 陷阱回归：`process_instance` / web `idempotency` 在哨兵值下复合唯一约束生效、拒绝重复；断言不产生 NULL 行。
- 键判据：不同租户可写相同 `business_key` / 相同 `Idempotency-Key` 而不互相污染；相同 `event_id` 仍全局唯一去重。
- 传播端到端：一条命令 → outbox 行 → Kafka `ce_tenantid` → 消费端 `CommandContext.tenantId` 一致。
- 跨租户隔离：租户 A 的查询不可见租户 B 的行（MP 拦截器 / JDBC 谓词 / PostgreSQL RLS 各一组）。
- RLS 边界：请求侧漏写谓词时 PostgreSQL RLS 仍拒绝跨租户；后台 relay/claim 用 BYPASSRLS 角色能扫到所有租户的行。
- 缺租户：`enabled=true` 且无法解析租户时按 `REJECT` 拒绝（400/401）。
- 开关关闭：`enabled=false` 时所有行落哨兵，行为等价于改造前。
- 迁移：存量单租户库 `ADD COLUMN` 后（仅租户相对键升级）不破坏既有数据。
- 后台轮询：全局 relay 正确把每行租户盖到出站 header。

---

## 十五、非目标（MVP）

跨库/跨 schema 路由、租户级配额与公平调度、租户级静态加密、租户自助生命周期管理、按租户分片的轮询与锁。
参考对比：Axon `axon-multitenancy` 采用"每租户独立基础设施段 + 租户走 Message MetaData"，隔离更强但更重；
本文吸收其"租户作为消息旁路元数据"一点，但基础设施保持共享（pool）。
