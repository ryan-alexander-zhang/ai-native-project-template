---
id: decision-00019-time-ordered-uuidv7-identifiers
type: decision
status: active
---

# 框架生成的 per-row 标识符改用时间有序 UUIDv7

> **补充（[[issue-00116-the-uuidv7-monotonicity-flake-was-the-wall-clock]]）：排序跟随墙钟，包括倒退。**
> JUG 的生成器只在同一毫秒内递增计数器，时间戳一变（**包括变小**）就重抽熵；而墙钟会被 NTP 校正拨回去。
> 所以时钟倒退时 id 会倒序。**没有钳位**，理由是本决定选 UUIDv7 的目的从头到尾是**写放大与索引局部性**，
> 不是排序保证：已核实框架里没有任何地方按 id 排序（outbox 按 `created_at` + 自增标识列，PM 按 `seq`，
> deadline 只把 id 当决定性平局打破），唯一性也来自熵而非时钟。
> 需要单调**作为保证**的应用需要的是序列，不是时钟——这句已写进 `Uuidv7IdGenerator` 的 javadoc。


固化"框架为每行/每条消息铸造的标识符（command messageId、integration `event_id`、process 实例/迁移/effect/deadline id、
operation-log `recordId` 等）应采用**时间有序 UUIDv7**，经统一的 `IdGenerator` SPI 生成、默认由成熟库实现"这一决策。
与多租户（[[decision-00018-multi-tenancy-boundaries]]）**正交**：那里管的是低基数的 `tenant_id`（要窄+不可变、明确不用 UUIDv7），
这里管的是高基数、高频插入的 per-row id。承接 [[decision-00013-command-context-and-causation-propagation]]（id 由 bus/durable
runtime 铸造、业务不自造）、[[decision-00016-durable-runtime-staged-message-identity]]（staged 身份铸造点）。

## 结论先行

> **框架铸造的所有 per-row / per-message 标识符改用 UUIDv7（时间有序），取代现状的 `UUID.randomUUID()`（v4）。
> 引入一个 framework-free 的 `IdGenerator` SPI（`aipersimmon-ddd-core`，纯接口零依赖），默认实现 `Uuidv7IdGenerator`
> 由一个成熟库（JUG / uuid-creator）在独立 impl 模块提供、经 autoconfig 装配；现有各处 `UUID.randomUUID()` 的铸造点
> 统一收口到注入的 `IdGenerator`（多数已是可注入 supplier）。选 UUIDv7 而非 ULID（保持 UUID 形态、无缝替换，且全库无
> `UUID.fromString` 解析假设）。前向兼容、零数据迁移：v4 老行与 v7 新行共存。不涵盖 `tenant_id`、客户端提供的 web key、
> 边缘 requestId、lease WorkerId，以及消费方自有的聚合业务键。**

## Context

实测各表主键布局揭示随机 id 的伤害**不均匀**（下表 = 动机）：

| 表 | 主键（聚簇索引） | 随机 UUID 位置 | 随机插入影响 |
|---|---|---|---|
| `process_instance` / `transition` / `effect` / `deadline` | **VARCHAR id 主键** | 就是主键 | **最大**（InnoDB 聚簇索引被随机打散） |
| `saga` / `saga_deadline` | `correlation_id` VARCHAR 主键 | 主键 | 大 |
| `inbox` | `(consumer, message_key)` | `message_key` 第二列 | 中 |
| `outbox` / `dead_letter` | **`id` BIGINT IDENTITY（顺序）** | `event_id` 二级唯一索引 | 小 |
| `operation_log` | `record_id` VARCHAR（DDL 注释已标 "UUIDv7/ULID"） | 主键 | 设计即待 v7 |

即：**扩展性痛点主要在 process-manager 与 saga 的随机 VARCHAR 聚簇主键**；outbox 因主键已是自增 BIGINT，只有二级唯一索引
`event_id` 受随机插入影响，量级更轻。随机 v4 在数据量大时导致 B-tree 页分裂、随机写、buffer pool 命中率下降——即"没有时序
插入不具扩展性"的经典问题。UUIDv7 把时间戳放高位，使新 id 单调递增、索引近似**尾部追加**，恢复插入局部性。

两处实测事实（决定选型）：全代码库**无 `UUID.fromString`**（id 皆当不透明 String，v7/ULID 均安全）；**无现成 uuid 生成依赖**，
且 Java 21 `java.util.UUID` 不能生成 v7（须引库或手写）——owner 已定：**引成熟库**。

## Decision

1. **per-row id 采用 UUIDv7**，取代 `UUID.randomUUID()`（v4）。选 v7 而非 ULID：保持 36 字符 UUID 形态、对现有
   `UUID.randomUUID().toString()` 无缝替换；`VARCHAR(64)` 下宽度不吃紧，ULID 换形态的收益不值当。使用库的**同毫秒单调**
   变体，避免突发插入时同毫秒内乱序丢失局部性。
2. **引入 `IdGenerator` SPI**（`aipersimmon-ddd-core`，`String newId()`，纯接口、零依赖，保持 core framework-free）。
   默认实现 `Uuidv7IdGenerator` 放**独立 impl 模块**（携带库依赖）、经 `@AutoConfiguration` 以 `@ConditionalOnMissingBean`
   装配——沿用 observability「SPI in framework-free 模块 / 实现在 impl 模块」的既有拆法。
3. **收口所有框架铸造点到注入的 `IdGenerator`**：`RegistryCommandBus`（messageId，已是可注入 supplier）、
   `OutboxWriter`（jdbc + mybatis-plus，`event_id`）、`SpringIntegrationEvents`（in-process `event_id`）、
   process-manager 的 id supplier、operation-log engine 的 `recordId` supplier（现默认 v4，本决策落定其 DDL 注释早已声明的 v7）。
   `correlationId`（= 根 messageId）与 `causationId`（= 上游 messageId）**自动继承** v7，无需单独处理。
4. **前向兼容、零迁移**：不改任何 DDL（列已是 VARCHAR）；v4 老行与 v7 新行合法共存、各自全局唯一；收益仅对新插入累积。
5. **不涵盖**：`tenant_id`（低基数、要窄+不可变，见 [[decision-00018-multi-tenancy-boundaries]] 命题四）、客户端提供的
   web idempotency/nonce/bucket key、web 边缘 `requestId`（非持久化键）、lease `WorkerId`（非高频 PK）、消费方自有聚合业务键
   （由消费方自定，框架仅建议）。
6. **默认开启**：id impl 模块进标准 starter/BOM，v7 成为默认；SPI fallback（无 impl 时退回 `UUID.randomUUID()`）仅为
   framework-free / 极简装配保留，保证不强加硬依赖。

## 命题

**命题一：outbox 是不是最该改的？——不是。**
outbox/dead_letter 主键已是自增 `BIGINT id`（聚簇索引顺序追加），relay 轮询按 `created_at,id` 顺序键；随机的只有二级唯一索引
`event_id`，插入会有页分裂但比"随机聚簇主键"轻一个量级。**收益最高的是 process-manager/saga 的随机 VARCHAR 主键**。改
`event_id` 为 v7 是顺带的低成本收益。

**命题二：v7 暴露创建时间要紧吗？**
UUIDv7 高位含毫秒时间戳，`event_id` 会随 Kafka header 外传、部分 id 可能经 API 暴露 → 泄露粗粒度创建时刻。对内部消息/记录
id 属可接受权衡；若某 id 有强不可预测性要求（非本框架现状），该 id 应显式排除。默认接受。

**命题三：为什么不全用数据库自增/序列？**
id 需在**插入前**于应用侧铸造（outbox 行与 `EventEnvelope` 在同一事务内先有 id 才能盖章、消费端据 `event_id` 去重）；
分布式下无协调的全局唯一是硬需求。BIGINT 序列做不到跨库无协调，故保留应用侧铸造、仅改其算法为 v7。

## Consequences

- 正面：高基数 PK/唯一索引插入局部性改善，process-manager/saga 在大数据量下的写放大与页分裂显著缓解；operation-log 兑现其
  DDL 早已声明的 time-ordered id；改动集中在已存在的 supplier 注入点。
- 代价：新增一个第三方库依赖（限于 id impl 模块，core 仍零依赖）；v7 泄露粗粒度创建时间（命题二）；需一个 impl 模块 + BOM 条目。
- 风险低：id 皆不透明 String、无格式解析假设；v4/v7 共存无需迁移；无 impl 时行为回退等价现状。

## Alternatives considered

- **ULID**：更窄（26 vs 36 字符）、同为时间有序；因换 id 形态、`VARCHAR(64)` 下宽度收益有限，且偏离现有 UUID 形态而否决。
- **手写 v7 生成器**：零依赖，但同毫秒单调与并发正确性需自研自测；owner 已选引库。
- **数据库序列/自增 BIGINT 全表**：无法应用侧预铸造 + 无跨库无协调唯一性（命题三），否决。
- **维持 v4**：即本决策要解决的扩展性问题，否决。
