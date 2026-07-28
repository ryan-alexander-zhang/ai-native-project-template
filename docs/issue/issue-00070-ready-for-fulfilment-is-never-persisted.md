---
id: issue-00070-ready-for-fulfilment-is-never-persisted
type: issue
role: main
status: open
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

另有一条现成的反向证据可直接引用：`OrderListPagingTest.aLineTotalIsSummedBySqlNotByLoadingTheAggregate`
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

未修。本 issue 由 [[report-00002-scaffold-ddd-review]] 落盘，尚未实施。

## 关联

- [[report-00002-scaffold-ddd-review]]
- [[issue-00068-stock-waits-have-no-deadline-and-can-park-forever]]（订单卡住时无法自助取消，两者叠加后果最重）
- [[design-00004-durable-process-manager-runtime]]
