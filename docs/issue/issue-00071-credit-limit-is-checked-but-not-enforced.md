---
id: issue-00071-credit-limit-is-checked-but-not-enforced
type: issue
role: main
status: open
parent: report-00002-scaffold-ddd-review
---

# 信用额度被检查、但没有被强制：跨聚合不变量走了既非强一致也非最终一致的第三条路

## 问题（现状，file:line 为证）

- **等级：Medium（并发下可无限超额；作为 scaffold 的示范意义更大——它是全项目唯一一处跨聚合不变量，读者会照抄）**。
- `PlaceOrderHandler` 在 `Order.place` **之后**做额度检查（`PlaceOrderHandler.java:105-110`）：

```java
Order order = Order.place(orderId, customerId, lines, review);
if (!customer.canAfford(order.total())) {
  throw new CreditExceededException(...);
}
```

- 这是一条**横跨 `Customer` 与 `Order` 两个聚合**的规则，被放在应用服务里裸检查，且：
  - **没有任何并发保护**。`V3__aggregate_version.sql:22-23` 明确写道
    `ordering.customers` 故意不加 version 列，理由是 "`Customers` exposes only findById,
    so the Customer aggregate is never written and a version column there would be dead weight"。
    该理由在"客户资料"这个层面成立，但它同时意味着**没有任何东西会因为下单而变化**——
    于是同一客户并发下 N 单，每单都独立读到同一个 `credit_minor = 100000` 并各自通过检查，
    总额可以是额度的任意倍数。
  - 检查发生在聚合**创建之后**（`Order.place` 已经 `registerEvent` 了 `OrderPlacedEvent`
    和可能的 `OrderReadyForFulfilmentEvent`，见 `Order.java:87-90`），靠抛异常回滚兜住。
    功能上没问题，但顺序上是"先造后验"。
- `Customer.canAfford`（`Customer.java:23-25`）比较的是**单笔订单金额 vs 额度**，
  而不是"已用额度 + 本笔 vs 额度"。即便串行下单，第二笔 60000 也会通过 100000 的检查——
  **这条规则实际表达的是"单笔限额"，不是"信用额度"**，而命名、错误码
  （`ordering.credit-exceeded`）和专属 problem type（`OrderingProblemCatalog.java:26-29`
  "the client shows a top-up flow"）都在说后者。

## 根因（第一性）

1. **观察 vs 期望**：期望"信用额度是一条被强制的业务规则"；
   实际"它是一次对陈旧快照的建议性比对，且比对的口径也不是额度"。
2. **最小机制**：`Customers` 只有 `findById`（`Customers.java:10`），没有 `save`；
   `ordering.customers` 没有 version 列。**没有写入 ⇒ 没有争用点 ⇒ 没有任何东西可以串行化两笔并发下单。**
   数据库层面也没有约束能表达"该客户所有未完成订单金额之和 ≤ credit_minor"。
3. **真根因**：跨聚合不变量在 DDD 里只有两条正路——
   (a) 把它收进**同一个聚合**并在同一事务里强制；
   (b) 接受**最终一致**，事后用补偿流程追回。
   这里走的是第三条：**在应用服务里做一次无保护的读时检查，并把它当作已经强制了**。
   `CreditExceededException` 有专属错误码和专属 problem type，整条链路都在把它呈现为
   一条硬规则，唯独强制它的机制不存在。
4. **排除的伪根因**：不是 `V3` 的决定写错了——在"`Customer` 从不被写入"这个前提下，
   不加 version 列是对的。错的是那个前提本身：**如果额度要被强制，`Customer` 就必须被写入**
   （扣减已用额度），前提就不成立了。这也正是
   [[issue-00086-customer-is-an-aggregate-nothing-writes]] 的另一面。

## 复现（test-first）

```java
@Test
void concurrentOrdersCannotBetweenThemExceedTheCreditLimit() throws Exception {
  seedCustomer("CUST-CREDIT", 100_000);            // 额度 100000
  CyclicBarrier bothLoaded = new CyclicBarrier(2);

  // 两笔各 60000 的订单并发下单：任意一笔单独看都在额度内，两笔之和 120000 超额
  List<Future<?>> both = submitBoth(() -> place("CUST-CREDIT", 60_000, bothLoaded));

  long placed = both.stream().filter(this::succeeded).count();
  assertEquals(1, placed, "两笔之和超出额度，只能有一笔成功");
}
```

当前必然得到 `placed == 2`。
（barrier 的编排手法可直接照抄 `ConcurrentApprovalTest.StageTheRace`，
把装饰对象从 `Orders` 换成 `Customers`。）

## 修复

三选一，**必须明确选一个并写进注释**——目前最糟的地方是没有立场：

1. **强一致（推荐，且顺带补一个 scaffold 现在缺的示范）**：
   把"已用额度"建模进 `Customer` 聚合——`Customer.reserveCredit(Money)` 扣减、
   `releaseCredit(Money)` 在订单取消时归还；`Customers` 加 `save`；
   `ordering.customers` 加 version 列（撤销 `V3` 的那条豁免）。
   下单在同一事务里写两个聚合——这与 inventory 已有的"刻意的多聚合事务"是同一种权衡，
   scaffold 已经为它写过论证（`ReserveStockHandler.java:34-45`），此处可以援引。
2. **最终一致**：保留读时检查，但明确标注它是**建议性**的，
   并加一条对账/补偿流程（超额订单事后取消）。这条更接近真实信贷系统的做法。
3. **改名承认现状**：若只想演示"单笔限额"，就把
   `CREDIT_EXCEEDED` / `canAfford` / `creditLimit` 改成 `SINGLE_ORDER_LIMIT` 一类的名字，
   并去掉 "top-up flow" 的说法。代价最小，但放弃了一次演示跨聚合一致性的机会。

无论哪条，都应在 `PlaceOrderHandler` 里留一句注释说明**这是有意识的选择**——
scaffold 的其它权衡处（多聚合事务、hybrid 编排）都有这样的注释，唯独这里没有。

## 验证结果

未修。本 issue 由 [[report-00002-scaffold-ddd-review]] 落盘，尚未实施。

## 关联

- [[report-00002-scaffold-ddd-review]]
- [[issue-00086-customer-is-an-aggregate-nothing-writes]]（同一根因的另一面）
- [[design-00011-aggregate-persistence-contract]]（version 列与 `VersionedRow` 契约）
- [[issue-00051-aggregates-have-no-optimistic-locking]]（`V3` 的来源）
