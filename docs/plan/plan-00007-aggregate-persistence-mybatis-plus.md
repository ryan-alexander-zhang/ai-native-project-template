---
id: plan-00007-aggregate-persistence-mybatis-plus
type: plan
role: main
status: open
parent: design-00001-aipersimmon-ddd-and-scaffold
---

# 聚合落 PostgreSQL（MyBatis-Plus）+ per-BC schema 隔离

把 `multi-module` 的业务聚合从内存 `ConcurrentHashMap` 改为**真正持久化到 PostgreSQL**（MyBatis-Plus 实现），
让聚合与 outbox 落在**同一个库、同一个事务**——消除 [[issue-00027-outbox-atomicity-broken-by-in-memory-aggregate]]
的假原子性，兑现 [[plan-00006-middleware-integration]] 的"真正集成 PostgreSQL"，并作为 [[design-00006-integration-event-routing]]
outbox 可靠性的硬前提（D4）落地。

**验收锚点**：(1) `POST /orders` 下单后重启应用，订单仍在（真持久化，非内存）；(2) **原子性**——注入 outbox 写失败，
断言聚合一并回滚（内存态下写不出的这个回归，落库后才可写，见 issue-00027 §复现）；(3) 现有流程/契约测试（已跑在真实
Postgres Testcontainers 上）继续全绿。

## 一、Design

### 1.1 现状 → 目标

| | 现状 | 目标 |
| --- | --- | --- |
| 聚合持久化 | `InMemoryOrders`/`InMemoryCustomers`/`InMemoryStocks`/`InMemoryReservations`（内存 map） | MyBatis-Plus 适配器，落 PostgreSQL |
| 数据访问栈 | outbox/inbox = jdbc 变体，聚合无持久层 | **全栈 MyBatis-Plus**：聚合 + outbox/inbox 走 `-mybatis-plus` 变体（PM 无 MP 变体，保持 jdbc） |
| PG 里存什么 | 仅 PM/outbox/inbox 基础设施表 | + 业务聚合表 |
| 原子性 | 假（聚合内存、outbox PG，不同事务） | 真（同库同事务） |

payment 无持久聚合（授权是 `AuthorizationPolicy` 计算，无状态）——不建表。

### 1.2 schema 布局（单库、per-BC 隔离聚合、共享 infra）

```
数据库 ordering（单库、单 DataSource）
├── schema ordering   : orders, order_lines, customers        ← ordering BC 聚合
├── schema inventory  : stocks, reservations                  ← inventory BC 聚合
└── schema public     : aipersimmon_outbox, aipersimmon_inbox,
                        aipersimmon_dead_letter, shedlock, PM 四表   ← 共享消息/基础设施
```

- 聚合按 BC 隔离（对齐 modular-monolith "每模块独立 schema、禁跨 BC join/FK"）。
- outbox 等**共享 infra 表留 `public`**：单 port → 单 outbox 的设计不拆逐 BC outbox（避免多 writer/relay 的复杂度）。
- **DataSource 默认 schema = public**（组件迁移建无前缀表落此）；聚合 DO 用 `@TableName(schema=...)` 显式限定。

### 1.3 原子性保证（关键，回应"隔离后 outbox 还同事务吗"）

**是，仍同事务。** PostgreSQL schema 只是库内命名空间；一个 Spring 事务 = 一个 DataSource = 一条连接 = 一个数据库，
**可跨 schema 原子提交**。命令总线已有 `TransactionCommandInterceptor` + `TransactionTemplateUnitOfWork` 在 handler 外开
真实事务；MyBatis-Plus（MyBatis-Spring `SqlSessionTemplate`）与 outbox 都走**同一个 DataSource 连接**，自动入该事务。

```mermaid
flowchart LR
  cmd["PlaceOrder handler<br/>(TransactionCommandInterceptor 事务)"]
  cmd -->|MyBatis-Plus| a[("ordering.orders / order_lines")]
  cmd -->|OutboxWriter (mybatis-plus)| o[("public.aipersimmon_outbox")]
  a & o --> commit{一个事务<br/>一起提交/回滚}
```

混用 MyBatis-Plus（聚合/outbox/inbox）+ jdbc（PM）不影响原子性——同 DataSource 即同事务。**handler / domain / application 一行不改**。

### 1.4 领域保持 framework-free

仓储端口（`Orders`/`Customers`/`Stocks`/`Reservations`）留在 domain 不变；MyBatis-Plus 的实体 DO 与 mapper 放
infrastructure；适配器在 DO ↔ 领域聚合之间转换。ArchUnit "domain 不依赖 Spring/MyBatis" 照旧通过。

## 二、Tasks（分阶段，低耦合）

- **P0 — MyBatis-Plus 底座 + 全栈切换（不动聚合语义）**
  - `start/pom.xml` 加 MyBatis-Plus Spring Boot starter；outbox/inbox 依赖由 `-jdbc` 换 `-mybatis-plus` 变体
    （表结构相同，`flyway.components` 不变）。配置 mapper 扫描（含库变体 mapper 包与应用 mapper 包）。
  - 验收：现有 18 tests 仍全绿（outbox/inbox 换栈后行为一致）；DataSource 默认 schema 保持 public。
- **P1 — schema + 聚合 DDL（应用自有 Flyway）**
  - 新增 `start/src/main/resources/db/migration`（消费者自有迁移，早于组件迁移跑）：`CREATE SCHEMA ordering/inventory`
    + `orders`/`order_lines`/`customers`/`stocks`/`reservations` 表 + customers 种子。乐观锁列 `version`（若聚合有 `Version`）。
  - 验收：启动后 psql 见两 BC schema 与表；组件表仍在 public。
- **P2 — MyBatis-Plus 适配器替换 `InMemory*`**
  - 每个 BC infrastructure 层实现端口：`Stock`/`Reservation`/`Customer` 单表用 `BaseMapper` 近零样板；`Order` 带
    `order_lines` 一对多，适配器在 save/find 时手动装配子表。DO 用 `@TableName(schema=...)`。删除 `InMemory*`。
  - 验收：流程测试（下单→预留→收款→确认 / 支付拒绝补偿）在真实 PG 上跑通，数据可 psql 查得。
- **P3 — 原子性 + 持久化回归**
  - 新增原子性测试：令 outbox 写失败（约束冲突/注入），断言 `orders` 无该行（聚合与 outbox 一起回滚）。
  - 新增持久化测试：save 后新事务/新上下文 findById 命中（证明真落库、非内存）。

## 三、验收路径

1. 全 reactor `mvn -pl start -am verify` 绿（真实 Postgres + Kafka 容器）。
2. 重启应用后既有订单仍可查（持久化）。
3. 原子性：outbox 失败 → 聚合回滚（issue-00027 的回归，现可写且通过）。
4. domain framework-free：ArchUnit 通过（聚合不依赖 MyBatis/Spring）。
5. 现有流程/契约测试逐字全绿（handler/domain 未改）。

## 四、非目标与边界

- 不做 CQRS 读模型、事件溯源、JPA/Hibernate。
- **schema 隔离仅覆盖业务聚合**；outbox/inbox/PM/shedlock 作为共享 infra 留 public，**不逐 BC 拆 outbox、不分库**
  （彻底隔离/拆分就绪属 [[issue-00031-flyway-shared-schema-and-bundled-shedlock-table]] / [[issue-00028-broker-transport-on-single-deployable-monolith]] 范畴）。
- 不改 `IntegrationEvents` port、不改 handler/domain/application、不新增事务代码（复用既有拦截器）。

## 五、关联

- [[issue-00027-outbox-atomicity-broken-by-in-memory-aggregate]]（本计划直接解决）
- [[design-00006-integration-event-routing]]（D4：本计划为其 outbox 可靠性前提）
- [[plan-00006-middleware-integration]]（现场；六.已记为后续 plan-00007）
- [[decision-00006-integration-event-transport-selection]]（outbox 同事务原子性）
- reference：`modular-monolith-with-ddd`（每模块独立 schema、禁跨模块 join/FK）
