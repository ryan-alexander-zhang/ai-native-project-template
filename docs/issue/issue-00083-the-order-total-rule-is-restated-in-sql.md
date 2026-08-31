---
id: issue-00083-the-order-total-rule-is-restated-in-sql
type: issue
status: resolved
blocks: [report-00002-scaffold-ddd-review]
---

# 订单总额的计算规则在 SQL 里被重述了一遍，且靠一个隐含假设成立

## 问题（现状，file:line 为证）

- **等级：Low（当前两边算得一样；但金额口径有两个独立实现，其中一个还依赖对方的不变量）**。
- 写侧的权威口径在聚合里（`Order.java:182-187`）：

```java
public Money total() {
  return lines.stream().map(OrderLine::subtotal).reduce(Money::plus)
      .orElseThrow(() -> new DomainException("order has no lines"));
}
```

  经 `OrderLine.subtotal()`（`:43-45`）= `unitPrice.times(quantity)`，
  而 `Money.plus` 会**强制同币种**（`Money.java:40-44`）。

- 读侧在 SQL 里重算一遍（`OrderListMapper.java:41-42`）：

```sql
COALESCE(SUM(l.quantity * l.unit_minor), 0) AS totalMinor,
MAX(l.currency)                             AS currency
```

- 两点值得记下：
  - **口径重复**。"总额 = Σ(数量 × 单价)"这条规则现在有两处独立实现。
    今天它们一致；一旦写侧引入行折扣、税、赠品行（`quantity × unit` 不再等于小计），
    SQL 这份**不会跟着改**，而且不会有任何测试失败——
    `OrderListPagingTest.aLineTotalIsSummedBySqlNotByLoadingTheAggregate`（`:131-141`）
    断言的是 `300`，一个两侧都算得出的数。
  - **`MAX(l.currency)` 是一个假设，不是一个规则**。它对"一张订单只有一种币种"这件事
    没有任何表达力——只是任取一个。该假设由领域侧的 `Money.plus` 保证
    （混币种时 `Order.total()` 会抛），但 SQL 不知道这件事：
    真出现混币种行时，`SUM` 会把不同货币的数值**直接相加**，得到一个无意义的数，
    再配上任取的一个币种码。写侧会拒绝的数据，读侧会安静地展示出来。
  - `COALESCE(..., 0)` 让"无行订单"在读侧显示为 0，
    而写侧 `Order.total()` 对同样的对象**抛异常**（`:186`）。
    两侧对同一个非法状态给出相反的答案。

## 根因（第一性）

1. **观察 vs 期望**：期望"一条业务计算规则只有一处定义"；
   实际"有两处，且只有一处知道它的前置条件"。
2. **最小机制**：CQRS 的读侧刻意绕开聚合直接读表（这是对的，
   `OrderQueries.java:6-14` 与 `FindOrderHandler.java:15-19` 都论证得很好）。
   绕开聚合的同时也绕开了聚合携带的**规则**——包括 `Money` 的同币种强制。
3. **真根因**：把"读侧不该重建聚合"正确地推导成了"读侧自己算"，
   但漏掉了中间一步——**能在写入时固化的派生值，不该在每次读取时重算**。
   订单总额在下单后就不再变化（行集只在 placement 时设定，
   `MyBatisOrders.java:52` 自己说 "an order's line set is small and only set at placement"），
   它是一个**可以物化的值**，而不是一个必须重算的聚合。
4. **排除的伪根因**：不是"读侧不该用 SQL 聚合"——对真正动态的统计，SQL 聚合正是对的。
   问题只在于这一个值**不动态**，却被当成动态的算了。

## 复现（test-first）

```java
@Test
void theListTotalMatchesTheAggregateTotal() {
  String id = placeOrder(TENANT, customer, /* qty */ 3);
  long fromList = itemFor(list(TENANT, customer, null, 20), id).path("totalMinor").asLong();
  long fromAggregate = orders.findById(new OrderId(id)).orElseThrow().total().amountMinor();
  assertEquals(fromAggregate, fromList, "读写两侧的金额口径必须一致");
}
```

这条现在会通过——它的价值是**在口径分叉的那一天**变红。
真正能立刻变红的是混币种一条：

```java
@Test
void aMixedCurrencyOrderCannotBeSummedByTheListQuery() {
  // 直接用 JdbcTemplate 插入两行不同币种的 order_lines（绕过写侧的保护），
  // 断言列表查询不会给出一个把 USD 和 EUR 相加的数字。
}
```

## 修复

按代价排序：

1. **物化总额（推荐）**：`ordering.orders` 加 `total_minor` / `currency` 两列，
   由 `MyBatisOrders.toRow` 从 `order.total()` 写入。
   读侧直接 `SELECT o.total_minor`——**LEFT JOIN 和 GROUP BY 一起消失**，
   列表查询退化成单表扫描，顺带解决
   [issue-00073-no-index-supports-the-cursor-paged-list](issue-00073-no-index-supports-the-cursor-paged-list.md) 的一半代价。
   口径回到一处：聚合。
2. 若要保留 SQL 聚合：至少把假设写成约束——
   `order_lines` 加一条"同一订单币种唯一"的检查（触发器或
   `UNIQUE (tenant_id, order_id, currency)` 配合应用层保证），
   并把 `MAX(l.currency)` 换成 `MIN`/`MAX` 一致性断言，
   同时在 `OrderListMapper` 的 javadoc 里写明它依赖 `Money.plus` 的同币种保证。
3. `COALESCE(..., 0)` 的分歧：写侧既然认为无行订单非法（且 `Order.place` 拒绝创建，
   `Order.java:72-74`），读侧就不该为它准备一个显示值。

## 验证结果

已修。**采用修复方案 1（物化总额）**，方案 2 因此不需要，方案 3 随之消失。

- `V7__order_total.sql` 给 `ordering.orders` 加 `total_minor` / `currency`，
  三步走（可空 → 回填 → `SET NOT NULL`），回填刻意复刻旧的读侧表达式：
  对迁移前写入的行，那个表达式**就是**它们的历史总额。
- `MyBatisOrders.toRow` 从 `order.total()` 写入两列——口径回到聚合这一处。
- `OrderListMapper` 的 `LEFT JOIN` 与 `GROUP BY` 一起消失，查询退化为单表扫描，
  如本 issue 所预测，顺带让 `orders_by_customer_newest_first`（V4）覆盖整条语句。
  `MAX(l.currency)` 与 `COALESCE(..., 0)` 两个问题随之不复存在——
  前者是"猜测而非规则"，后者让读侧给非法状态准备了一个显示值。
- 复现段第一条（读写口径一致）落为 `theListedTotalIsTheAggregatesOwnTotal`。
  它今天通过、修复前也会通过——**价值在口径分叉的那天变红**，这一点写进了测试 javadoc。
  第二条（混币种）没有单独写：物化之后读侧根本不再做跨行求和，
  混币种在写侧就被 `Money.plus` 拒绝，SQL 里已经没有可以出错的地方。

顺带清理了两处因这次改动而失效的描述：`OrderListPagingTest` 的类 javadoc
（"Each row is one SQL join with the line totals summed"）与用例名
`aLineTotalIsSummedBySqlNotByLoadingTheAggregate` → `aPageIsAnsweredWithoutRehydratingAnyOrder`
（[issue-00070-ready-for-fulfilment-is-never-persisted](issue-00070-ready-for-fulfilment-is-never-persisted.md) 里的引用已同步更新）。
留着它们就正好是本次评审反复在修的那类文档漂移。

## 关联

- [report-00002-scaffold-ddd-review](../report/report-00002-scaffold-ddd-review.md)
- [issue-00073-no-index-supports-the-cursor-paged-list](issue-00073-no-index-supports-the-cursor-paged-list.md)（方案 1 同时改善它）
- [issue-00077-money-arithmetic-has-no-overflow-guard](issue-00077-money-arithmetic-has-no-overflow-guard.md)（SQL 侧的 `SUM` 同样没有溢出保护）
