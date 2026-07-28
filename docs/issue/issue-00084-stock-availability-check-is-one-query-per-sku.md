---
id: issue-00084-stock-availability-check-is-one-query-per-sku
type: issue
role: main
status: open
parent: report-00002-scaffold-ddd-review
---

# 库存可用性查询是每个 SKU 一次往返，而它在下单的同步热路径上

## 问题（现状，file:line 为证）

- **等级：Low（小订单无感；但它位于唯一的同步跨上下文调用上，且该调用被 README 当作"快速失败"卖点）**。
- `CheckStockAvailabilityHandler` 逐 SKU 查库（`CheckStockAvailabilityHandler.java:27-34`）：

```java
return query.skus().stream()
    .map(sku -> new StockLevel(sku, stocks.findBySku(new Sku(sku)).map(Stock::available).orElse(0)))
    .toList();
```

  `Stocks.findBySku` 只有单条形态（`Stocks.java:12`），
  `MyBatisStocks.findBySku` 是一次 `selectById`（`:44-51`）。
  **N 个 SKU = N 次数据库往返**。
- 它在 `PlaceOrder` 的同步路径上：
  `PlaceOrderHandler.java:83-84` → `StockAvailabilityGatewayAdapter.check`（`:35-36`）
  → `StockAvailabilityService.check`（`:35-41`）→ 上面这个 handler。
  也就是说**每次下单都要多做 N 次往返**，N = 订单去重后的行数（上限 100，
  `Order.MAX_LINES`）。
- 额外一点：这里为了读一个 `available` 整数，**重建了 N 个 `Stock` 聚合**
  （`Stock.reconstitute`，`MyBatisStocks.java:49-50`），
  而 `FindOrderHandler` 的 javadoc（`:16-19`）与 `OrderQueries` 的 javadoc（`:6-11`）
  恰恰花了很大篇幅论证"读侧不该重建聚合"。
  **同一个项目在读侧同时示范了正确做法和它自己批评的做法。**

## 根因（第一性）

1. **观察 vs 期望**：期望"一次可用性查询 = 一次数据库往返"；
   实际"= 行数次往返，且每次重建一个聚合"。
2. **最小机制**：`Stocks` 这个**领域仓储端口**只提供按聚合根 id 的单条加载——
   这对仓储是正确的（仓储的职责就是按 identity 存取聚合）。
   查询侧复用了它，于是继承了它的粒度。
3. **真根因**：inventory 上下文**没有读侧端口**。
   ordering 有 `OrderQueries`（`ordering-application`，与 `Orders` 分开，
   并在 javadoc 里明确"It is *not* a repository"）；
   inventory 只有 `Stocks` 这一个写侧仓储，于是查询用例只能借它。
   缺的不是一个批量方法，而是**读写分离在 inventory 侧没有落地**。
4. **排除的伪根因**：不是"应该缓存"——缓存是另一层的优化，
   且会引入一致性问题；一次批量查询就够了。
   也不是"这个同步查询本身不该存在"——它的存在是有论证的
   （`StockAvailabilityGateway.java:13-17`、`PlaceOrderHandler.java:30-36`），论证成立。

## 复现（test-first）

```java
@Test
void checkingTenSkusIsOneRoundTripNotTen() {
  CountingDataSource counting = ...;                      // 或用 datasource-proxy / p6spy 计数
  queryBus.ask(new CheckStockAvailability(tenSkus()));
  assertEquals(1, counting.selectCount(),
      "可用性检查应一次查完；当前是每个 SKU 一次");
}
```

若不想引入计数基础设施，退一步的结构断言也能钉住意图：

```java
@Test
void theAvailabilityQueryDoesNotGoThroughTheAggregateRepository() {
  assertFalse(usesBean(CheckStockAvailabilityHandler.class, Stocks.class),
      "读侧应走 StockQueries 读端口，而不是写侧仓储");
}
```

## 修复

与 ordering 侧对称，给 inventory 补一个读端口：

1. `inventory-application` 新增 `StockQueries`（读端口，非仓储）：

```java
public interface StockQueries {
  /** 每个请求的 SKU 的当前可用量；未收录的 SKU 记 0。一次查询答完。 */
  List<StockLevel> levelsOf(List<String> skus);
}
```

2. `inventory-infrastructure` 新增 `MyBatisStockQueries` + 一条
   `WHERE sku IN (...)` 的 mapper 语句（租户谓词由拦截器补，
   与 `OrderListMapper.java:22-25` 同理，**不要手写**）。
3. `CheckStockAvailabilityHandler` 改依赖 `StockQueries`。
4. 实现类用 `@Component` 而非 `@Repository`——理由照抄
   `MyBatisOrderQueries.java:18-20` 那段（它不是领域仓储端口的实现）。

顺带：`StockLevel` 的 javadoc（`:6-9`）说"未收录与缺货对调用方看起来一样"，
批量实现要保持这个语义（`IN` 查不到的 SKU 也要补一行 `available = 0`），
否则会悄悄改变发布契约的行为。

## 验证结果

未修。本 issue 由 [[report-00002-scaffold-ddd-review]] 落盘，尚未实施。

## 关联

- [[report-00002-scaffold-ddd-review]]
- [[decision-00015-cross-context-sync-query-via-gateway-acl]]（这条同步查询的设计依据）
- [[issue-00076-inventory-relies-on-an-upstream-invariant-to-protect-itself]]（同一 handler 家族的另一处重复加载）
