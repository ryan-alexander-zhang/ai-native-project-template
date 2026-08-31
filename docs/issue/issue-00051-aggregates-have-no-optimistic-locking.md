---
id: issue-00051-aggregates-have-no-optimistic-locking
type: issue
status: resolved
blocks: [report-00001-ddd-framework-review]
---

# 业务聚合没有乐观锁：并发命令各自通过状态机守卫后互相覆盖，聚合的一致性边界在聚合外被静默破坏

## 问题（现状，file:line 为证）

- **等级：Critical（静默数据损坏 + 违反 DDD 最核心承诺；无需异常配置即可在生产触发）**。
- 框架**任何一层都不提供聚合版本号**：
  - `aipersimmon-ddd-core` `model/AbstractAggregateRoot.java:23` 只有 `domainEvents` 一个字段，**无 `version`**；
    类体（`:21-51`）仅 `registerEvent` / `checkInvariant` / `domainEvents` / `clearDomainEvents`。
  - `model/Entity.java` 只有 `id()`；`model/AggregateRoot.java` 是空扩展接口。
- 样例聚合表**无 version 列**：`start/src/main/resources/db/migration/V1__aggregates.sql` 的
  `ordering.orders` 为 `(id, customer_id, status)`。
- 样例仓储是裸的读-改-写，**无版本谓词**：`MyBatisOrders.java:41-45`

  ```java
  if (orders.selectById(id) == null) { orders.insert(header); } else { orders.updateById(header); }
  ```

- **冲突→409 链路目前是死代码**：`ConcurrencyTranslationCommandInterceptor.java:25-26` 专门捕获
  `OptimisticLockingFailureException` 翻译为 `ConcurrencyConflictException`（→ 409），但**框架内没有任何一处
  会对业务聚合抛出它**——没有版本化写入，就没有 `OptimisticLockingFailureException`。整条设计存在却永不触发。

**对照：流程管理器自己做对了。** `processmanager/model/ProcessRevision.java` +
`exception/StaleProcessRevisionException.java` + `engine/store/ProcessInstanceStore.java` 是完整的版本化写入。
**能力已在仓库内，只是没有给业务聚合用。**

## 根因（第一性）

1. **观察 vs 期望**：期望「聚合 = 一个事务一致性单元，并发修改必须有一方失败」；实际「并发修改双方都成功，后写者
   覆盖前写者，且两份领域事件都发出去」。
2. **最小机制**：聚合的状态转换守卫（`Transitions` 表 / `OrderLifecyclePolicy`）是**纯内存判定**，它只检查
   「我加载到的这个状态 → 目标状态」是否合法。它对「我加载之后、我写回之前，别人改过没有」**结构上无从知晓**——
   因为没有任何东西把「加载时的版本」带到写回的 `WHERE` 子句里。守卫因此退化为**建议**而非**约束**。
3. **真根因**：聚合缺少「加载版本」这一身份的一部分。不是 `Transitions` 表写错了，不是 `OrderLifecyclePolicy`
   判定不严，也不是 `ConcurrencyTranslationCommandInterceptor` 位置不对——三者都正确；缺的是让它们生效的前提：
   **写回时校验读取快照仍然有效**。
4. **排除的伪根因**：不是「事务隔离级别不够」。即使 `SERIALIZABLE`，两条命令若落在**不同事务、串行执行**
   （HTTP 并发下的常态），也是合法的先后两次读-改-写，数据库无从判断第二次写基于的是陈旧快照——这类
   丢失更新必须由应用层的版本谓词（或 `SELECT ... FOR UPDATE` 悲观锁）解决。

### 破坏路径一：库存超卖（业务可见的资金损失，最严重）

`inventory` 侧 `Stock` 聚合被并发写，且**没有版本校验**：

- `Stock.java:24-35`：`reserve(int)` 内 `if (quantity > available) throw`，随后 `available -= quantity`——
  守卫与扣减都基于**加载到的内存值**。
- `ReserveStockHandler.java:63-76`：先整体校验所有行，再逐行 `stock.reserve(...)` + `stocks.save(stock)`。
- `inventory.stocks` 表为 `(sku, available)`，**无 version**（`V1__aggregates.sql`）。

于是同一 SKU 的两个并发预留：

```
SKU-1 初始 available = 10
T1: findBySku → available=10      T2: findBySku → available=10
T1: reserve(8) 通过守卫 (8 ≤ 10)   T2: reserve(8) 通过守卫 (8 ≤ 10)
T1: save → available=2            T2: save → available=2      ← 覆盖 T1，值恰好相同故更隐蔽
T1: Reservation(8) 落库            T2: Reservation(8) 落库
```

**结果：库存表显示 available=2，但已对外承诺 16 件（两张各 8 件的 Reservation），实际只有 10 件。**
超卖 6 件。两条命令都返回成功，`StockReserved` 集成事件都发了出去，下游照常收款发货。

这个路径比状态机路径更危险，因为**最终值恰好相同（都是 2）**，任何「对账 available 是否合理」的检查都发现不了；
只有把 `Reservation` 汇总与 `available` 对账才能看出缺口。

### 破坏路径二：订单状态机被绕过

`FULFILMENT_IN_PROGRESS` 状态下，**确认**与**因库存失败取消**都合法：

- `Order.java:38`：`.allow(FULFILMENT_IN_PROGRESS, CONFIRMED)` → `order.confirm()` 合法
- `OrderLifecyclePolicy.java:70-73`：`ensureInventoryCancellationAllowed` 要求
  `status == FULFILMENT_IN_PROGRESS` → `order.cancel(InventoryUnavailable)` 同样合法

于是 `ConfirmOrderHandler.java:37-41` 与 `CancelOrderHandler.java:37-41` 并发时：

```
T1: findById → status=FULFILMENT_IN_PROGRESS   T2: findById → status=FULFILMENT_IN_PROGRESS
T1: confirm() 通过 Transitions 守卫             T2: cancel() 通过 OrderLifecyclePolicy 守卫
T1: save → status=CONFIRMED                    T2: save → status=CANCELLED   ← 覆盖 T1
T1: 发布 OrderConfirmedEvent                    T2: 发布 OrderCancelledEvent
```

**两条命令都返回成功，两份互相矛盾的领域事件都进了 outbox**，订单最终状态取决于写入竞争的偶然顺序。下游流程
管理器会同时收到「已确认」与「已取消」两个事实——而它的 `ProcessDefinition` 按 step 分派，会把其中一个当作
乱序事实 `ignore` 掉，于是**不一致被永久固化，且不留任何错误痕迹**。

## 复现（test-first）

> 实现时两条复现合并为一个测试类 `ConcurrentAggregateWriteTest`（见「验证结果」）：超卖路径是主用例，
> 陈旧写入被拒是它依赖的、不带时序的确定性用例。状态机路径（次复现）由同一个版本谓词保证，未单独建测。

**主复现**：`start/src/test/java/com/example/ConcurrentAggregateWriteTest.java`（Testcontainers PostgreSQL，
两个真实并发事务）——直接证明超卖：

1. 置 `SKU-1` 的 `available = 10`。
2. 两个线程各 `commandBus.send(new ReserveStock(orderId_N, [line(SKU-1, 8)]))`，用 `CyclicBarrier` 在
   `findBySku` 之后、`save` 之前对齐，制造确定性交错。
3. **断言（现状 → 失败）**：期望恰好一方成功、另一方失败（`ConcurrencyConflictException` 或
   `INSUFFICIENT_STOCK`）；实际**两方都成功**，且
   `sum(reservation_lines.quantity) == 16 > 10 == 初始 available`——超卖成立。
4. 修复后：恰好一方成功；`sum(reservation_lines.quantity) + stocks.available == 10` 这条守恒式始终成立
   （这是比「状态对不对」更强的断言，作为长期回归守卫）。

**次复现**：`ConcurrentOrderModificationTest` —— 证明状态机被绕过：

1. 置一个订单到 `FULFILMENT_IN_PROGRESS`。
2. 两个线程分别 `commandBus.send(new ConfirmOrder(id))` 与
   `commandBus.send(new CancelOrder(id, InventoryUnavailable(...)))`，同样用 `CyclicBarrier` 对齐。
3. **断言（现状 → 失败）**：期望恰好一个线程抛 `ConcurrencyConflictException`；实际两个都正常返回，
   且 `ordering.orders.status` 只保留后写者的值，outbox 里同时存在 `OrderConfirmedEvent` 与 `OrderCancelledEvent`。
4. 修复后：恰好一方成功、另一方抛 `ConcurrencyConflictException`（Web 层映射 409），outbox 只有胜者那一份事件。

补充单测 `aipersimmon-ddd-core` `AbstractAggregateRootTest`：新建聚合 `version()==0`；rehydrate 后为持久化值；
`versionAdvanced()` 后 +1。

## 修复

分两批（见 [plan-00013-phase-one-correctness-remediation](../plan/plan-00013-phase-one-correctness-remediation.md)）：

- **批次 A（正确性基线）**：`AbstractAggregateRoot` 增加 `version`（`0` = 未持久化）+ `restoreVersion` /
  `version()` / `versionAdvanced()`；**三张被写入的聚合表**加 `version BIGINT NOT NULL DEFAULT 1`（新迁移）：
  `ordering.orders`、`inventory.stocks`、`inventory.reservations`。（`ordering.customers` 只读——`Customers`
  端口仅有 `findById`，无 `save`——故**不加**，保持改动面最小。）
  对应 DO 用 MyBatis-Plus `@Version` 声明式加 `WHERE version = ?`；仓储检查 affected-rows，`0` 行则抛
  `OptimisticLockingFailureException`，由既有 `ConcurrencyTranslationCommandInterceptor` 翻译成 409。
  顺带消掉 `selectById + insert` 的 TOCTOU：以 `version() == 0` 区分新建/更新，不再靠先查一次。
  **默认值是 `1` 而非 `0`**：`0` 被保留表示「尚未持久化」，若已有行以 `0` 迁入，仓储会把它们当作新聚合而走
  INSERT 并撞主键。（这是实施中发现并修正的一处自伤。）
- **批次 B（易用性）**：抽出框架侧版本化仓储基类，把「版本谓词 + affected-rows 检查 + 事件发布」收成默认路径，
  见 [design-00011-aggregate-persistence-contract](../design/design-00011-aggregate-persistence-contract.md)。

**⚠ 拦截器组合陷阱（必须同批处理，否则本 issue「看起来修了但实际没修」）**：MyBatis-Plus 只认**一个**
`MybatisPlusInterceptor` bean，而 `AipersimmonDddTenancyMybatisPlusAutoConfiguration:35-45` 已用
`@ConditionalOnMissingBean(MybatisPlusInterceptor.class)` 注册了自己那一个。若新增一个同样条件的乐观锁
autoconfig，则**开启多租户时它静默退让**，`@Version` 不生成 `WHERE version = ?`，`updateById` 恒返回 1，
超卖照旧。批次 A 的处置：让**样例自己**组合一个含 `TenantLineInnerInterceptor` + `OptimisticLockerInnerInterceptor`
的 `MybatisPlusInterceptor`（tenancy 按其既有文档整体退让）；框架侧的 `InnerInterceptor` 贡献模型留给批次 B，
见 [design-00011-aggregate-persistence-contract](../design/design-00011-aggregate-persistence-contract.md) §3。

**注意改动面**：批次 A 触及 `core`（新增字段与三个方法）、样例 DDL（新迁移）、3 个 DO + 3 个仓储、
样例的 MyBatis-Plus 拦截器组合；不改任何仓储端口签名，但 **`Order.reconstitute` 等 rehydrate 工厂要多带一个
version 参数**（破坏性，无外部使用者，可接受）。

## 验证结果（批次 A，已修复）

回归守卫：`start/src/test/java/com/example/ConcurrentAggregateWriteTest.java`（Testcontainers PostgreSQL）。

**证明它能捕获本缺陷**：把 `MyBatisStocks.save` 临时改回修复前的读-改-写后重跑，两个测试双双变红——

- `aWriteFromAStaleSnapshotIsRejected`：`Expected OptimisticLockingFailureException to be thrown,
  but nothing was thrown`（陈旧快照的写入被静默接受）；
- `concurrentReservationsOfOneSkuCannotOversell`：`exactly one reservation may win ==>
  expected: <1> but was: <2>`——**超卖如本 issue 所述精确复现**（两个预留都成功）。

恢复修复后两者转绿，且 `available + 已预留 == 初始库存` 的守恒式成立。

冲突→409 链路不再是死代码：仓储抛 `OptimisticLockingFailureException`，由既有
`ConcurrencyTranslationCommandInterceptor` 译为 `ConcurrencyConflictException`。

样例全量 `mvn -f aipersimmon-ddd-scaffold/multi-module/pom.xml verify` 通过——含真实 Postgres + Kafka 的
`OrderingFlowTest` / `ReviewFlowTest` / `PaymentCompensationFlowTest` / `TwoTenantAcceptanceTest`，
证明版本化写入与拦截器组合没有破坏既有流程或租户隔离。

批次 B（仓储基类，把正确写法变成默认路径）见 [plan-00013-phase-one-correctness-remediation](../plan/plan-00013-phase-one-correctness-remediation.md)，属易用性改进，
本 issue 的缺陷已闭环。

## 关联

- [report-00001-ddd-framework-review](../report/report-00001-ddd-framework-review.md)（P0-1，本 issue 的来源）
- [plan-00013-phase-one-correctness-remediation](../plan/plan-00013-phase-one-correctness-remediation.md)
- [design-00011-aggregate-persistence-contract](../design/design-00011-aggregate-persistence-contract.md)（批次 B 的仓储基类方向）
- [issue-00052-domain-events-lost-when-publish-and-clear-forgotten](issue-00052-domain-events-lost-when-publish-and-clear-forgotten.md)（同一处仓储调用点的姊妹缺陷，批次 A 一并修）
- [issue-00055-aggregate-root-missing-identity-equality](issue-00055-aggregate-root-missing-identity-equality.md)（同在 `AbstractAggregateRoot`，一并修）
- [design-00004-durable-process-manager-runtime](../design/design-00004-durable-process-manager-runtime.md)（流程管理器已有的 `ProcessRevision` 版本化写入，本 issue 的正面对照）
