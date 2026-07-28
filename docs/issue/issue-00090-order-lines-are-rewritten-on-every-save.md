---
id: issue-00090-order-lines-are-rewritten-on-every-save
type: issue
role: main
status: open
parent: report-00002-scaffold-ddd-review
---

# 子行集在每一次聚合保存时被整体重写，包括只改状态的那些保存

## 问题（现状，file:line 为证）

- **等级：Low（当前数据量下无感；但它让每次状态变更都产生与行数成正比的写放大，且写入的是与本次变更无关的数据）**。
- `MyBatisOrders.saveChildren` 无条件 delete + 逐行 insert（`MyBatisOrders.java:52-69`）：

```java
lines.delete(new LambdaQueryWrapper<OrderLineDo>().eq(OrderLineDo::getOrderId, id));
for (int i = 0; i < lineData.size(); i++) { ...; lines.insert(row); }   // 逐行，非批量
```

- 它在**每一次** `save` 时都跑，而订单的一生里 `save` 会被调用多次：
  - `PlaceOrderHandler` / `FulfilmentTrigger.begin`（`:41`）
  - `ConfirmOrderHandler:37`、`CancelOrderHandler:35`、`CancelOwnOrderHandler:48`
  - 其中后三者**只改了 `status` 一个字段**，行集一个字节都没变。
- 类注释自己给出了理由（`MyBatisOrders.java:52`）：
  "an order's line set is small and only set at placement, so it is rewritten wholesale"。
  **前半句成立，恰恰是后半句的反证**——正因为行集只在下单时设定，
  之后的每一次重写都是纯粹的浪费。
- `MyBatisReservations.saveChildren`（`:51-62`）同构，且更明显：
  `ReleaseStockHandler` 保存 `Reservation` 时只翻了 `released` 一个布尔
  （`ReleaseStockHandler.java:52,65`），却要重写全部 `reservation_lines`。
- 具体代价，按一张 3 行订单算：
  - 每次状态变更 = 1 DELETE + 3 INSERT + 1 UPDATE(header)，而不是 1 UPDATE；
  - DELETE 的谓词是 `(tenant_id, order_id)`，目前**无索引**
    （见 [[issue-00073-no-index-supports-the-cursor-paged-list]]），
    所以这次 DELETE 是全表扫；
  - 逐行 `insert` 而非 `insertBatch`，N 行 = N 次往返；
  - 行的物理版本被反复更新，令 `order_lines` 的膨胀（PostgreSQL dead tuple）
    与变更次数成正比，而不是与真实数据变化成正比。

## 根因（第一性）

1. **观察 vs 期望**：期望"保存一个聚合时写入的是**变化了的**部分"；
   实际"写入的是聚合的**全部**部分"。
2. **最小机制**：`MybatisPlusAggregateRepository` 的模板方法把
   "写根" 与 "写子" 绑成一次 `saveAggregate`，
   而 `saveChildren(Order)` 的签名里**没有任何东西能表达"子集合是否变过"**——
   它只拿得到聚合的当前状态，拿不到它与上一次持久化状态的差。
3. **真根因**：聚合没有**变更追踪**（dirty tracking）。
   这是一个刻意的简化，而且大体上是对的——手写变更追踪很容易出错，
   ORM 的脏检查又会把持久化关注点渗进领域模型（这个项目明确在避免，
   `Order.java:96-97` 说 "no persistence annotations here"）。
   代价就是"全量重写"成为唯一可行的子集合策略。
4. **排除的伪根因**：不是"应该上 JPA"——引入 ORM 的代价远大于本 issue 的收益。
   也不是"delete+insert 本身错了"——对**确实变了**的行集，这是最简单正确的做法。

## 复现（test-first）

```java
@Test
void confirmingAnOrderDoesNotRewriteItsLines() {
  String orderId = place("CUST-1", "SKU-1", 3);
  long linesWrittenBefore = countStatements("insert into ordering.order_lines");

  commandBus.send(new ConfirmOrder(orderId));

  assertEquals(linesWrittenBefore, countStatements("insert into ordering.order_lines"),
      "只改状态的保存不应重写行集");
}
```

（`countStatements` 用 datasource-proxy / p6spy 之类的语句计数器；
若不想引入，可退而断言 `order_lines` 的 `xmin` 未变——PostgreSQL 特有但足够精确。）

## 修复

按代价排序，**前两条都很便宜**：

1. **让"是否重写子集合"成为聚合能回答的问题**：给 `Order` 加一个
   `linesDirty` 标志（只在 `place`/`reconstitute` 之外的行集变更时置位——
   目前根本没有这种变更，所以恒为 false），
   `saveChildren` 据此短路。语义清晰，且不把持久化概念带进领域——
   "我的行集变过吗"是聚合自己知道的事。
   更彻底的做法是把这个能力上提到库的 `MybatisPlusAggregateRepository`
   （`saveChildren` 增加一个 `boolean childrenChanged` 参数或默认 no-op 钩子）。
2. **批量插入**：`lines.insert(row)` 逐行 → `insertBatch`，N 次往返变 1 次。
   这条与 1 正交，且对下单路径（唯一真正需要写行的路径）直接有效。
3. **补索引**：`(tenant_id, order_id)` 上的索引让那次 DELETE 从全表扫变成索引扫——
   随 [[issue-00073-no-index-supports-the-cursor-paged-list]] 一起做。

同时建议更新 `MyBatisOrders.java:52` 的注释：
把"行集很小所以整体重写"改成说明**为什么此处不做变更追踪**，
以及它的代价边界（"行集只在 placement 设定，因此其它保存的重写是纯开销"）。
这是这段代码真正值得留给读者的信息。

## 验证结果

未修。本 issue 由 [[report-00002-scaffold-ddd-review]] 落盘，尚未实施。

## 关联

- [[report-00002-scaffold-ddd-review]]
- [[issue-00073-no-index-supports-the-cursor-paged-list]]（DELETE 谓词的索引）
- [[design-00011-aggregate-persistence-contract]]（`MybatisPlusAggregateRepository` 的模板方法契约）
