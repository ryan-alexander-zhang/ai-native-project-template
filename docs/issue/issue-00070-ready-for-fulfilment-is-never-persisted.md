---
id: issue-00070-ready-for-fulfilment-is-never-persisted
type: issue
role: main
status: resolved
parent: report-00002-scaffold-ddd-review
---

# `READY_FOR_FULFILMENT` 从未落库：应用层把两个状态压成一个，客户自助取消在主流程不可达

## 问题（现状，file:line 为证）

- **等级：High（一个被 README 列为"已演示"的能力，在主流程上不可达；且订单一旦卡住，客户与运维都没有出路）**。
- `PlaceOrderHandler` 对免审订单**在同一事务里立刻**推进流程（`PlaceOrderHandler.java:116-119`）：

```java
} else {
  fulfilmentTrigger.begin(order, context);   // 立刻
}
```

- `FulfilmentTrigger.begin` 第一件事就是推状态（`FulfilmentTrigger.java:39-43`）：

```java
public void begin(Order order, CommandContext context) {
  order.beginFulfilment();                   // READY_FOR_FULFILMENT -> FULFILMENT_IN_PROGRESS
  orders.save(order);
  integrationEvents.publish(reservationRequest(order), context);
}
```

- 于是一张**全新的订单是以 `FULFILMENT_IN_PROGRESS` 被 INSERT 的**。
  `Order.place` 里设的初始态 `READY_FOR_FULFILMENT`（`Order.java:83-84`）只在内存里存在了几微秒，
  **从来没有一行 `ordering.orders` 记录处于该状态**。
- 后果落在自助取消上。`CancellableByCustomer.BEFORE_FULFILMENT` 是
  `{AWAITING_REVIEW, READY_FOR_FULFILMENT}`（`CancellableByCustomer.java:32-33`），
  其中第二个状态永不落库 ⇒ **只有被人工审核拦下的订单才可能被客户取消**。
- 这不是推论，是 scaffold 自己的测试所证明的：`SelfCancelTest` 两条用例都必须先用
  `SKU-RESTRICTED` 造一张 `AWAITING_REVIEW` 的订单（`SelfCancelTest.java:71-74`、`:102-107`），
  否则没有可取消的对象。
- 而 `README.md:82` 把「`Specification` answers, `Invariant` refuses」列进了"已演示能力"表。
  演示是真的，只是仅存在于审核这条边缘路径上。

## 根因（第一性）

1. **观察 vs 期望**：期望"`OrderStatus` 里区分出来的每个状态都是一个**可被观察到的持久事实**"；
   实际"其中一个状态只是一次方法调用之间的瞬时值"。
2. **最小机制**：`PlaceOrderHandler` 与 `ApproveReviewHandler` 都在**同一个事务**里
   完成"变成 ready"和"开始 fulfilment"两步。事务是原子的，所以中间态对外不可见——
   包括对数据库、对读模型、对客户。
3. **真根因**：`FulfilmentTrigger` 把两件语义不同的事捆成了一个方法。
   - "订单清结了（ready）"是**本上下文自己的**事实，由审核结果决定；
   - "fulfilment 开始了（in progress）"是**外部工作真的启动了**的事实。
   把后者提前到"我刚发出预留请求"这一刻，等于用"我请求了"冒充"它开始了"。
   `OrderStatus` 的注释（`OrderStatus.java:8-10`）自己说 `FULFILMENT_IN_PROGRESS` 是
   "the pivotal state — once an order enters it the customer can no longer cancel on their own"。
   把这个 pivot 前移到下单瞬间，就等于取消了自助取消这个能力。
4. **排除的伪根因**：不是 `CancellableByCustomer` 的窗口定义写窄了——
   `{AWAITING_REVIEW, READY_FOR_FULFILMENT}` 是对的；错的是其中一个状态活不到被观察。
   也不是"下单即锁定库存"的业务选择——真锁定发生在 inventory 侧的 `ReserveStock`，
   而那是**异步**的，此刻还没发生。

## 复现（test-first）

```java
@Test
void aReviewFreeOrderIsCancellableByItsCustomerBeforeFulfilmentActuallyStarts() {
  String order = placeOrder(TENANT, "CUST-1", "SKU-1");    // 免审，不是 SKU-RESTRICTED

  // 当前：状态已经是 FULFILMENT_IN_PROGRESS，下面两条都会失败
  assertEquals("READY_FOR_FULFILMENT", snapshot(order).path("status").asText());
  assertTrue(snapshot(order).path("cancellableByCustomer").asBoolean());
  assertEquals(204, cancel(order, "CUST-1").getStatusCode().value());
}
```

另有一条现成的反向证据可直接引用：`OrderListPagingTest.aPageIsAnsweredWithoutRehydratingAnyOrder`
（issue-00083 修复时由 `aLineTotalIsSummedBySqlNotByLoadingTheAggregate` 改名）
（`:140`）断言刚下单的订单状态是 `FULFILMENT_IN_PROGRESS`——它把当前行为**钉住**了，
修复时这条断言必须一起改。

## 修复

把"变 ready"与"开始 fulfilment"拆回两个时刻：

1. `PlaceOrderHandler` / `ApproveReviewHandler` 只负责把订单变成 `READY_FOR_FULFILMENT` 并 `save`，
   由仓储 drain 出 `OrderReadyForFulfilmentEvent`；
2. `FulfilmentTrigger` 退化为"发布 `OrderReadyForFulfilment` 集成事件 + 启动流程实例"，
   **不再调 `order.beginFulfilment()`**；
3. `beginFulfilment()` 改由流程管理器在收到 `StockReserved` 时驱动
   （新增一条 `BeginFulfilment` 命令，或并入既有的 `RequestPayment` 效果）。
   这一步同时让 `FULFILMENT_IN_PROGRESS` 名副其实：库存真的被占住了。

代价与收益：多一次聚合写入（`READY_FOR_FULFILMENT` 那次 INSERT），
换回一个真实存在的自助取消窗口、一个语义正确的状态机，以及 README 那一行不再是空头支票。

README 的「Intentional design decisions」一节（`README.md:155-160`）把
"Reservation is triggered from the ready moment, by the application" 列为有意设计——
该说明本身仍然成立（第一次预留请求由应用发起，而非流程效果），
本 issue 要改的不是**谁发起预留**，而是**何时推进聚合状态**。这两件事目前被同一个方法捆在一起。

## 验证结果

已修。三步全做，另外补了修复方案没有提到的一件必须做的事（见下）。

1. `FulfilmentTrigger.begin` 不再调 `order.beginFulfilment()`：只保存 **ready** 的订单并发布
   `OrderReadyForFulfilment`。订单从此以 `READY_FOR_FULFILMENT` 落库。
2. 新增 `BeginFulfilment` 命令 + handler（如方案 3 所说，独立命令而非并入 `RequestPayment`），
   由流程管理器在收到 `StockReserved` 时与 `RequestPayment` 一起派发。
   `FULFILMENT_IN_PROGRESS` 从此名副其实：库存真的被占住了。
3. 类注释按方案要求改写；README 的流程图、要点段与能力表同步更新。
   方案里"预留由应用发起而非流程效果"这一条设计说明仍然成立，注释里明确说了只有**推进聚合状态的时刻**变了。

**方案没写、但不做就会造成库存泄漏的一件事：自助取消与预留的竞态。**
`READY_FOR_FULFILMENT` 一旦真实可达，自助取消窗口就与预留过程重叠——
客户可以在 inventory 还在干活时取消，而 inventory 随后为一张已不存在的订单回 `StockReserved`。
不处理的话有两个后果：`BeginFulfilment` 撞上 `CANCELLED` 订单被聚合拒绝、效果中继重试到死信；
且那份库存永远回不来（补偿路径只从支付失败进入）——
与 [[issue-00094-a-swallowed-domain-exception-leaks-stock-permanently]] 同一类泄漏，从反方向到达。

处理方式利用了既有机制：`OrderCancelledEvent` 本来就经 `OrderFulfilmentStarter` 变成流程的
`OrderCancelled` 输入，此前在 `AWAITING_STOCK` 走 `ignore`。现在：
- 新增两个 Step（不改 state schema，枚举值是可加的）：
  `AWAITING_STOCK_ORDER_CANCELLED`（订单已取消，尚不知有无库存要还，**STOCK deadline 刻意保持武装**）
  与 `AWAITING_STOCK_RELEASE_ORDER_CANCELLED`（与既有 release 步骤只差一点：结尾不再派发
  `CancelOrder`，因为订单已经取消了，再派发会被聚合拒绝并毒化中继）。
- `AWAITING_PAYMENT` 也接 `OrderCancelled`，兜住"取消在流程已经推进之后才提交"的残余窗口。
- `BeginFulfilmentHandler` 对 `FULFILMENT_IN_PROGRESS`（效果重投递）与 `CANCELLED`（客户赢了）
  都是 no-op 并写明理由，所以残余窗口不会产生死信。

**还有一处必须跟着改，是三个测试红了才发现的**：
`OrderLifecyclePolicy.ensureInventoryCancellationAllowed` 要求
`status == FULFILMENT_IN_PROGRESS`。那条断言本身就编码了本 issue 的错误假设——
预留失败 / 预留超时现在发现订单**只是 ready**，于是补偿被拒。
已放宽为 `READY_FOR_FULFILMENT || FULFILMENT_IN_PROGRESS`，并保留对 `AWAITING_REVIEW` 的拒绝
（待审订单没有向 inventory 要过任何东西）。领域测试
`inventoryFailureDoesNotApplyBeforeFulfilment` 改名为
`inventoryFailureAppliesToAnOrderThatWasOnlyEverReady`，另补一条 `AWAITING_REVIEW` 的拒绝用例。

测试：
- `SelfCancelTest.anOrderNeedingNoReviewIsCancellableBeforeFulfilmentActuallyStarts` ——
  本 issue 复现段那条用例。订普通 SKU（不是 `SKU-RESTRICTED`），断言状态为 `READY_FOR_FULFILMENT`、
  `cancellableByCustomer` 为真、取消返回 204。
- `SelfCancelDuringReservationTest` —— 竞态的端到端：下单后立刻取消，然后要求流程
  COMPLETED/ORDER_CANCELLED、`reservations.released` 为真、库存回到原值、订单仍 CANCELLED、
  **且没有死信**。写这条测试时先犯了两个错并修掉：`processView` 必须绑定租户才查得到实例；
  以及"库存等于原值"这个断言在 t=0 就成立（预留是异步的），所以改为先等流程终态、
  再用 `released = true` 证明预留确实发生过又确实被释放。
- 负向对照：去掉 `AWAITING_STOCK` 的取消分支，流程在 30 秒窗口内一直 `RUNNING` 并持有预留。
- 按本 issue 的提示，两处钉住旧行为的断言一并改了：`OrderListPagingTest`（原
  `aLineTotalIsSummedBySqlNotByLoadingTheAggregate`，现
  `aPageIsAnsweredWithoutRehydratingAnyOrder`）与 `ConcurrentApprovalTest`，
  都改为 `READY_FOR_FULFILMENT` 并写明为什么。

代价与收益如方案所述：多一次聚合写入，换回一个真实存在的自助取消窗口、一个语义正确的状态机，
以及 README 那一行不再是空头支票。

验证：`mvn -o verify -pl start -am` 全绿，77 个测试 0 失败，Spotless / PMD / SpotBugs 通过。

## 关联

- [[report-00002-scaffold-ddd-review]]
- [[issue-00068-stock-waits-have-no-deadline-and-can-park-forever]]（订单卡住时无法自助取消，两者叠加后果最重）
- [[design-00004-durable-process-manager-runtime]]
