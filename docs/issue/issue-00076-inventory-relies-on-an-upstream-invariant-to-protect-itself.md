---
id: issue-00076-inventory-relies-on-an-upstream-invariant-to-protect-itself
type: issue
role: main
status: open
parent: report-00002-scaffold-ddd-review
---

# inventory 靠 ordering 的不变量才不出事：重复 SKU 会让同一聚合被加载两次

## 问题（现状，file:line 为证）

- **等级：Medium（当前不可触发，因为上游拦住了；但这正是限界上下文自治原则要防的那种耦合——上游一变，下游静默损坏）**。
- `ReserveStock` 对行内 SKU **不做唯一性校验**（`ReserveStock.java:26-35`）：
  只有 `@NotEmpty List<@Valid Line>`，`Line` 只有 `@NotBlank String sku` + `@Positive int quantity`。
- `ReserveStockHandler` 在两个循环里**各自独立加载**每行的 `Stock`
  （`ReserveStockHandler.java:70-84`，经 `stockFor(...)`，`:100-105`）。
  没有 identity map，同一 SKU 出现两行 ⇒ **同一个聚合被实例化成两个互不知情的对象**：

```java
for (Line line : command.lines()) { Stock s = stockFor(line.sku()); /* 校验 */ }
for (Line line : command.lines()) { Stock s = stockFor(line.sku()); s.reserve(q); stocks.save(s); }
```

  两行同 SKU 时：第一次 `save` 把 version 推到 N+1；第二个对象仍持 version N，
  它的 `save` 匹配 0 行 → `OptimisticLockingFailureException`。
- 该异常**不是** `DomainException`，逃出 `:90` 的 catch ⇒ **不发 `StockReservationFailed`**
  ⇒ 走 [[issue-00068-stock-waits-have-no-deadline-and-can-park-forever]] 的挂死路径。
- 目前不出事的唯一原因在**另一个上下文**里：`Order` 的
  `OrderHasDistinctSkus` 不变量（`OrderHasDistinctSkus.java:14-23`，由 `Order.place` 的
  `checkInvariant` 强制，`Order.java:86`）保证了 `OrderReadyForFulfilment` 的 lines 不重复。
- 也就是说：**inventory 的正确性由 ordering 的一条聚合不变量兜底**。
  两者之间隔着发布语言、outbox、Kafka、inbox——没有任何编译期或运行期机制维系这个依赖。

## 根因（第一性）

1. **观察 vs 期望**：期望"一个限界上下文对任何形状的入站命令都给出确定、可恢复的响应"；
   实际"对某一类形状，它给出的是一个不可恢复的技术异常"。
2. **最小机制**：`stockFor` 每次调用都 `stocks.findBySku(...)` 返回新对象
   （`MyBatisStocks.findBySku:44-51` 每次都 `Stock.reconstitute`）。
   聚合的身份不被会话追踪 ⇒ 同一行数据的两个副本可以同时存在于一个事务里 ⇒
   乐观锁把它们当成两个并发写者。
3. **真根因**：`ReserveStock` 这条命令的**前置条件没有被写在命令上**。
   "每个 SKU 至多一行"是 inventory 处理这条命令的**必要前提**，
   它却只在上游的订单聚合里被表达过一次。ACL（`OrderReadyForFulfilmentListener`）
   逐行平移了 payload（`:33-36`），没有在边界上做任何收敛——
   而"在边界上把外部形状规约成本上下文能处理的形状"正是 ACL 的职责。
4. **排除的伪根因**：不是乐观锁的问题——它的行为完全正确，
   是"同一聚合被加载两次"这件事本身就不该发生。
   也不是"双循环校验"的问题——先验后改是对的（见 `ReserveStockHandler.java:44-45`）；
   问题是两个循环都在重新加载。

## 复现（test-first）

绕过 ordering，直接给 inventory 发一条重复 SKU 的命令——这正是"BC 自保"要测的东西：

```java
@Test
void aReserveStockWithARepeatedSkuIsRejectedCleanly() {
  ReserveStock duplicated = new ReserveStock("order-dup", List.of(
      new ReserveStock.Line("SKU-1", 2),
      new ReserveStock.Line("SKU-1", 3)));

  // 期望：要么命令校验当场拒绝，要么合并成 SKU-1 x5 正常预留。
  // 当前：OptimisticLockingFailureException 逃出 handler，且不发 StockReservationFailed。
  assertDoesNotThrow(() -> commandBus.send(duplicated));
  assertNotNull(recorder.lastReservedOrFailedFor("order-dup"),
      "无论成败，inventory 都必须给出一个流程能消费的事实");
}
```

## 修复

三层，建议全做——它们防的是不同的东西：

1. **命令自保**：`ReserveStock` 的紧凑构造器合并同 SKU 行（`Line::sku` 分组求和），
   或加 `@AssertTrue` 拒绝重复。合并更友好：调用方的意图是明确的。
2. **handler 消除重复加载**：一次性把需要的 `Stock` 加载进一个
   `Map<Sku, Stock>`，两个循环共用同一批对象。
   这同时把加载次数从 2N 降到 N，并让"同一事务里同一聚合只有一个实例"成为结构性保证而非巧合。
3. **收窄失败语义**：`ReserveStockHandler` 的 catch 目前只认 `DomainException`，
   使得"业务失败"可恢复、"技术失败"静默挂死。应当明确区分并让两者都对流程可见——
   与 [[issue-00068-stock-waits-have-no-deadline-and-can-park-forever]] 的修复一并考虑。

顺带：`ReserveStockHandler` 的类注释（`:34-45`）详细论证了那个"刻意的多聚合事务"，
论证是成立的；但它没有提到**同一聚合不得在一个事务里被加载两次**这条隐含前提。
修复时应把这句话补进去——这是该权衡真正的边界条件。

## 验证结果

未修。本 issue 由 [[report-00002-scaffold-ddd-review]] 落盘，尚未实施。

## 关联

- [[report-00002-scaffold-ddd-review]]
- [[issue-00068-stock-waits-have-no-deadline-and-can-park-forever]]（本 issue 是逃出 catch 的一条具体路径）
- [[issue-00051-aggregates-have-no-optimistic-locking]]（version 机制本身；此处它工作正常）
- [[decision-00015-cross-context-sync-query-via-gateway-acl]]（ACL 的职责边界）
