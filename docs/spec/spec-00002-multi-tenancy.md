---
id: spec-00002-multi-tenancy
type: spec
role: main
status: active
parent:
---

# Spec: 原生多租户（Multi-Tenancy）MVP

> 一句话：让 `aipersimmon-ddd` 以 **pool 判别列 `tenant_id`** 原生隔离多租户——租户在信任边界解析后，
> 端到端随写路径、消息与耐久存储传播，读侧与基础设施强制按租户过滤；单租户是 N=1 的同一套 schema 与代码。

技术设计在 [[design-00009-multi-tenancy-tenant-id]]（本 spec 不内联设计）；决策边界见
[[decision-00018-multi-tenancy-boundaries]]。术语见 `CONTEXT.md` 的 Multi-Tenancy Language 区。

**MVP 范围** = design-00009 §一.6：租户原语组件（`aipersimmon-ddd-tenancy` + `-spring`）+ 写核心
（`cqrs`/`integration`）+ 耐久基础设施（outbox / inbox / process-manager / saga / web-store）+ PostgreSQL RLS
+ 可观测性维度。**不在本 spec**：silo（分库/分 schema）实际路由、按租户配额/公平调度、租户级静态加密、租户自助生命周期。
silo **seam** 预留在范围内（XFR-12），其路由实现不在。

## 1. Context

- 采用 `CONTEXT.md` Multi-Tenancy Language 的术语：**Tenant**、**Tenant Isolation Model**（Pool 选定 / Silo phase-2）、
  **Tenant Discriminator (`tenant_id`)**、**Sentinel Tenant (`__root__`)**、**TenantContext**、**Tenant-relative Key**、
  **Missing-Tenant Policy**。
- 受约束于 [[decision-00012-no-ambient-per-command-state]]（`TenantContext` 是 trusted-boundary 一次写入的不可变身份，
  非可变每命令状态——见 XFR-3）、[[decision-00013-command-context-and-causation-propagation]]（写侧权威载体为
  `CommandContext.tenantId`）、[[decision-00014-cloudevents-integration-event-contract]]（跨进程走扩展属性 + `ce_tenantid`）、
  [[decision-00016-durable-runtime-staged-message-identity]]（at-least-once 幂等不因租户改变）、
  [[design-00005-observability-and-distributed-tracing]]（trace 与租户分离传播）。
- **跨组件一致性**：全仓库共用**单一哨兵 `__root__`**。operation-log 链（analysis-00013 / decision-00017 /
  design-00008 / spec-00001）原用 `GLOBAL`，已统一改为 `__root__`（本 spec XFR-11 保留此约束以防回归）。

## 2. User Stories

多租户主要是框架横切能力，故按 small-spec 例外内联；story 面向**消费方开发者**与**平台运维**。requirement 若需全局引用，
用 `spec-00002-XFR-<i>`（本能力无独立 story-级 FR）。

- **US1（消费方开发者）租户随命令自动隔离**：我发一条命令，其所有聚合写入与产出的 integration event 都自动带上当前租户，
  无需在业务代码里手动传租户。
- **US2（消费方开发者）读取自动限定本租户**：我发一个查询，只返回当前租户的数据，漏写谓词也不会读到他人数据。
- **US3（消费方开发者）跨进程租户存活**：租户 A 产出的事件，被消费端以租户 A 的上下文处理，下游写入仍属 A。
- **US4（平台运维）单租户/关闭模式零负担**：`tenancy.enabled=false` 时全部落 `__root__`，行为与未引入多租户等价。

## 3. Cross-cutting / System Requirements

### 3.1 隔离与传播

- **spec-00002-XFR-1**（Ubiquitous）系统应在每条耐久行写入 `tenant_id`（`NOT NULL`），并在出站 Kafka 记录写 `ce_tenantid`。
- **spec-00002-XFR-2**（Event-driven）当命令在信任边界被接收，系统应把解析出的租户绑入 `CommandContext.tenantId`，
  并贯穿 `root/deriveChild/of(envelope)`、发布器盖章、`EventEnvelope` 扩展属性直至 `ce_tenantid`。
- **spec-00002-XFR-3**（Ubiquitous）`TenantContext` 应在 trusted boundary 一次写入、请求内不可变、只承载单个 `TenantId`；
  业务代码不得写入（对齐 decision-00012）。
- **spec-00002-XFR-4**（Unwanted）若消费端 `reconstruct` 收到带 `ce_tenantid` 的记录，系统**不得**丢弃该 header，
  应重建 `EventEnvelope` 租户并以 `TenantContext.runAs(tenant, …)` 包住处理。

### 3.2 空值与唯一键（正确性）

- **spec-00002-XFR-5**（Ubiquitous）`tenant_id` 应恒非空、默认 `__root__`；`TenantId` 校验应拒绝以 `__` 开头的用户租户。
- **spec-00002-XFR-6**（Ubiquitous）租户应仅进入**租户相对键**（`process_instance(tenant_id,process_type,business_key)`、
  web `idempotency`/`nonce`/`rate_limit` 的 PK），**不进**框架生成的全局唯一 id 去重键（`event_id`/`ce_id`/`correlation_id` 等）。

### 3.3 强制隔离（三后端 × RLS 边界）

- **spec-00002-XFR-7**（Where MyBatis-Plus 后端）系统应以原生 `TenantLineInnerInterceptor` 自动注入租户谓词与 INSERT 列，
  并 `ignoreTable` 排除 `shedlock` 与消息管道表。
- **spec-00002-XFR-8**（Where PostgreSQL 后端）系统应对**请求作用域表**（领域表/读模型/`process_instance` 请求侧检索）启用 RLS；
  H2/MySQL 无 RLS，退回手工 `TenantPredicate`。
- **spec-00002-XFR-9**（Where PostgreSQL RLS 后端）请求作用域事务应在事务内 `SET LOCAL app.tenant = <resolved>`；
  该设置为事务作用域，连接归还池后**不得**残留到下一个借用者（禁用 `SET SESSION`）。
- **spec-00002-XFR-10**（Unwanted）若后台 relay/claim/cleanup 轮询运行，系统应以**独立 BYPASSRLS 角色**连接、
  **不** `SET app.tenant`，从而跨租户全表扫描消息管道表（管道表不建 RLS）；该角色不得用于请求作用域领域访问。

### 3.4 策略、开关、一致性、观测

- **spec-00002-XFR-11**（Where 多租户开启）当请求解析不出租户时，`MissingTenantPolicy` 默认 `REJECT`（400/401）；
  全仓库共用单一哨兵 `__root__`（operation-log 链已对齐）。
- **spec-00002-XFR-12**（Optional/预留）系统应把请求作用域数据源暴露为**单一可替换 bean**（读 `TenantContext`），
  使将来的 `TenantRoutingDataSource`（silo）无需改 mapper 即可替入；MVP 提供单库 pass-through。
- **spec-00002-XFR-13**（Ubiquitous）系统应把租户作为维度加入 span（`tenant.id`）、MDC（`tenant`）；租户与 trace 分离传播。

**Acceptance（GWT）**

- **spec-00002-XAC-5.1**（XFR-5，NULL 陷阱）
  Given `process_instance` / web `idempotency` 复合唯一键、`tenant_id = __root__`
  When 同租户重复写同一 `business_key` / `Idempotency-Key`
  Then 唯一约束生效、拒绝重复；断言库中不存在 `tenant_id IS NULL` 行
- **spec-00002-XAC-6.1**（XFR-6，键判据）
  Given 租户 A 与 B
  When 各写相同 `business_key` 与相同 `Idempotency-Key`
  Then 两者互不冲突、互不返回对方缓存响应；而相同 `event_id` 仍全局唯一去重
- **spec-00002-XAC-2.1**（XFR-1/2，端到端传播）
  Given 租户 A 的一条命令
  When 经 outbox 行 → Kafka → 消费端
  Then outbox 行 `tenant_id`、`ce_tenantid`、消费端 `CommandContext.tenantId` 三者一致为 A
- **spec-00002-XAC-4.1**（XFR-4，consumer 存活）
  Given 一条带 `ce_tenantid=A` 的记录
  When 消费端 `reconstruct`
  Then `EventEnvelope` 租户为 A、处理在 `runAs(A)` 内，下游写入 `tenant_id=A`
- **spec-00002-XAC-8.1**（XFR-7/8，读隔离，参数化 MP/JDBC/RLS 三组）
  Given 库中同时存在 A、B 的行
  When 以租户 A 上下文查询
  Then 只返回 A 的行（MyBatis-Plus 拦截器 / 手工谓词 / PostgreSQL RLS 各验一组）
- **spec-00002-XAC-9.1**（XFR-9，连接池不泄漏）
  Given RLS 开启、一个被复用的池连接
  When 先服务租户 A 的事务、归还、再服务租户 B
  Then B 的事务只见 B 行；A 的 `app.tenant` 不残留（`SET LOCAL` 随事务结束失效）
- **spec-00002-XAC-8.2**（XFR-8，RLS 兜底漏写谓词）
  Given PostgreSQL RLS、一条请求侧 SQL 故意漏写租户谓词
  When 以租户 A 执行
  Then DB 仍只返回 A 行（策略拒绝跨租户），不依赖应用层谓词
- **spec-00002-XAC-10.1**（XFR-10，后台跨租户）
  Given 多租户库、A/B 均有待投递 outbox 行
  When 单一全局 relay 轮询
  Then BYPASSRLS 角色扫到 A、B 全部行，并各自把 `tenant_id` 盖到 `ce_tenantid`
- **spec-00002-XAC-11.1**（XFR-11，缺租户）
  Given `tenancy.enabled=true`
  When 请求无法解析租户
  Then 按 `REJECT` 拒绝（400/401），无任何写入或读取落到共享桶
- **spec-00002-XAC-11.2**（XFR-11，关闭模式）
  Given `tenancy.enabled=false`
  When 正常读写与投递
  Then 所有行 `tenant_id=__root__`，行为等价于引入多租户前
- **spec-00002-XAC-DDL.1**（迁移）
  Given 存量单租户库
  When 执行 `ADD COLUMN tenant_id NOT NULL DEFAULT '__root__'`（仅租户相对键表升级唯一约束）
  Then 既有数据不破坏、复合唯一约束在哨兵下成立

## 4. Technical Design

默认外置：机制、12 表 schema、传播链、模块清单见 [[design-00009-multi-tenancy-tenant-id]]。下列为 spec 级、
需在本 spec 阶段定死的**运维细节**（对应 decision-00018 "待 spec 明确"三项）：

### 4.1 RLS 策略与连接/事务生命周期
- 请求作用域表：`ALTER TABLE t ENABLE ROW LEVEL SECURITY; CREATE POLICY t_tenant ON t USING (tenant_id = current_setting('app.tenant', true));`（`true` = 缺失时返回 NULL 而非报错，配合 REJECT 前置守卫）。
- 每请求事务开始即 `SET LOCAL app.tenant = :tenant`（事务作用域，随 commit/rollback 失效，归还池零残留）。
- 连接池（HikariCP）：禁用任何 `SET SESSION app.tenant`；无需 connection-init-sql；由 `TransactionSynchronization` 或
  AOP 在事务边界注入 `SET LOCAL`。

### 4.2 数据库角色模型（最小权限）
- `app_request` 角色：对请求作用域表持 DML，**不含** BYPASSRLS——受策略约束。
- `app_worker` 角色：`BYPASSRLS` + 仅对消息管道表（outbox/dead_letter/process_effect/process_deadline/inbox）DML；
  供 relay/claim/cleanup 数据源使用，不用于领域访问。
- ArchUnit / 装配校验：后台轮询数据源必须绑定 `app_worker`，请求数据源必须绑定 `app_request`。

### 4.3 silo seam（预留、不实现路由）
- 请求作用域 `DataSource` 暴露为单一 `@Bean`，其解析当前租户的唯一来源是 `TenantContext.current()`。
- MVP 实现为直连单库；phase-2 用 `AbstractRoutingDataSource` 子类替换该 bean，`determineCurrentLookupKey()` 读
  `TenantContext`，mapper/`JdbcTemplate` 代码零改动。

### 4.4 配置键
- `aipersimmon.tenancy.enabled`（默认 false）、`aipersimmon.tenancy.missing-policy`（默认 `REJECT`）、
  `aipersimmon.tenancy.resolver.source`（header/subdomain/jwt-claim）、`aipersimmon.tenancy.resolver.header`。

## 5. 关联文档
- 决策：[[decision-00018-multi-tenancy-boundaries]]
- 设计：[[design-00009-multi-tenancy-tenant-id]]
- 术语：`CONTEXT.md` → Multi-Tenancy Language
- 已对齐：[[spec-00001-operation-log-component]] 等 operation-log 文档哨兵 `GLOBAL` → `__root__`
