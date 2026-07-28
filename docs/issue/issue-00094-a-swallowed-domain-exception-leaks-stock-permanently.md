---
id: issue-00094-a-swallowed-domain-exception-leaks-stock-permanently
type: issue
role: main
status: open
parent: report-00002-scaffold-ddd-review
---

# 吞掉 DomainException 让事务照常提交：预留失败会永久扣掉一部分库存

## 问题（现状，file:line 为证）

- **等级：High（真实的数据损坏：库存被扣掉且无法归还，因为没有 `Reservation` 可供释放。
  并且它与类注释的明确声明直接矛盾——读者会以为这条路径是安全的）**。
- `ReserveStockHandler.handle`（`ReserveStockHandler.java:67-97`）：

```java
try {
  for (Line line : command.lines()) {              // ① 先校验全部行
    Stock stock = stockFor(line.sku());
    if (line.quantity() <= 0 || line.quantity() > stock.available()) throw new DomainException(...);
  }
  Map<Sku, Integer> held = new LinkedHashMap<>();
  for (Line line : command.lines()) {              // ② 再逐行扣减并落库
    Stock stock = stockFor(line.sku());            //    ← 重新加载
    stock.reserve(line.quantity());
    stocks.save(stock);                            //    ← 这一行已经写进事务
    held.merge(...);
  }
  reservations.save(new Reservation(reservationId, command.orderId(), held));
  integrationEvents.publish(new StockReserved(...), context);
} catch (DomainException failure) {                // ③ 吞掉，不标记回滚
  integrationEvents.publish(new StockReservationFailed(...), context);
}
```

- 类注释（`:44-45`）声称：

  > "The validate-all-before-mutate-any loop above is what keeps a mid-line failure from
  > leaving a partial reservation **even before the transaction rolls back**."

  **事务不会回滚。** `catch` 吞掉了异常，方法正常返回，命令总线的
  `TransactionCommandInterceptor` 看到的是成功，于是提交。
  括号里那句 "even before the transaction rolls back" 假设了一个不存在的兜底。

- 两个循环之间**重新加载**了 `Stock`（②里的 `stockFor` 是第二次读）。
  默认 READ COMMITTED 下，另一个事务在两次读之间提交的扣减是**可见的**。于是：

  1. ①：SKU-A 可用 5、SKU-B 可用 5，请求各 5，校验通过；
  2. 另一事务扣掉 SKU-B 的 5 并提交；
  3. ②：SKU-A 扣 5 → `stocks.save` **写入当前事务**；SKU-B 重新读到 0 →
     `stock.reserve(5)` 抛 `INSUFFICIENT_STOCK`；
  4. ③：捕获 → 发 `StockReservationFailed` → 方法返回 → **事务提交**。

  结果：**SKU-A 的 5 件被扣掉，没有 `Reservation` 记录，`StockReservationFailed` 已发出，
  订单被补偿取消。** 那 5 件库存从此没有任何东西能归还它——
  `ReleaseStock` 按 `reservationId` 释放（`ReleaseStockHandler.java:40-47`），而它不存在。

- 同一条 catch 还掩盖了第二个问题的反面：**非** `DomainException` 的失败
  （`OptimisticLockingFailureException` 等）**不**被捕获，于是不发失败事件、
  流程永久停在 `AWAITING_STOCK`——那是
  [[issue-00068-stock-waits-have-no-deadline-and-can-park-forever]] 与
  [[issue-00076-inventory-relies-on-an-upstream-invariant-to-protect-itself]]。
  **同一个 catch 块，两个方向都错**：该回滚的没回滚，该上报的没上报。

## 根因（第一性）

1. **观察 vs 期望**：期望"预留要么全部成功，要么什么都不改"；
   实际"存在一条路径，它改了一部分、报告了失败、并把改动提交了"。
2. **最小机制**：Spring 的声明式事务按**异常是否逸出**决定回滚。
   `catch` 让异常不逸出 ⇒ 提交。而 handler 需要的是
   "回滚数据改动，但仍然发出一条失败事件"——这两件事在同一个事务里**互相排斥**，
   因为失败事件本身也是通过 outbox 写进同一个事务的。
3. **真根因**：把"用事件报告失败"（一个**跨上下文协议**决策，正确且有充分论证）
   与"用异常控制事务"（一个**技术机制**）叠加在了同一个出口上，
   而两者对"异常"的要求正好相反：协议要求把异常变成事件，事务要求让异常逸出。
   代码选了协议，于是静默失去了事务保护——**而注释以为两者都还在**。
4. **排除的伪根因**：
   - 不是"先验后改"写错了。这个模式是对的，只是它防的是**单事务内**的顺序问题，
     防不了两次读之间的并发变更。
   - 不是"应该用悲观锁"。乐观锁已经就位且工作正常
     （`ConcurrentAggregateWriteTest` 证明了它）——问题不在并发检测，
     在**检测到之后的处理**。
   - 不是"多聚合事务"这个权衡错了。该权衡（`:34-45`）成立；
     错的是它的实现没有兑现"要么全成要么全不成"。

## 复现（test-first）

确定性复现（不靠并发，用一个装饰器在两次读之间制造变更）：

```java
@Test
void aMidLineFailureLeavesNoStockDeducted() {
  seedStock("SKU-A", 5); seedStock("SKU-B", 5);

  // Stocks 装饰器：第 2 次 findBySku("SKU-B") 时，先在另一个事务里把 B 扣光
  stealBStockOnSecondRead();

  commandBus.send(new ReserveStock("order-x", List.of(
      new ReserveStock.Line("SKU-A", 5),
      new ReserveStock.Line("SKU-B", 5))));

  assertEquals(5, availableOf("SKU-A"),
      "预留失败后 SKU-A 必须一件不少 —— 当前是 0，且无 Reservation 可归还");
  assertEquals(0, reservationCountFor("order-x"));
  assertNotNull(recorder.failedFor("order-x"), "同时仍要发出 StockReservationFailed");
}
```

第二条钉住"失败事件仍然发出"，防止修复时把事件一起回滚掉——**这才是本 issue 的难点**。

## 修复

核心是把**数据改动**与**失败上报**放进不同的事务。三个可行方案：

1. **失败事件走独立事务**（推荐，改动最小）：
   catch 里先 `TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()`，
   失败事件通过一个 `REQUIRES_NEW` 的小组件发布。
   数据改动随主事务回滚，失败事件独立提交。
   注意：此时不能再依赖 outbox 的"与聚合同事务"保证——但对**失败**事件而言这是可接受的，
   因为它不需要与任何数据改动原子（没有改动了）。
2. **纯决策 + 单次落库**：把 handler 重构成"先算出完整结果，再一次性落库"——
   一次加载全部 `Stock` 进 `Map<Sku, Stock>`（顺带修掉
   [[issue-00076-inventory-relies-on-an-upstream-invariant-to-protect-itself]] 的重复加载），
   在内存里完成全部 `reserve`，任何一行失败就**在写任何东西之前**转入失败分支。
   这样 catch 里根本没有已提交的改动要撤销，现有结构可以保留。
   **这个方案最贴合本项目的风格**——`ProcessDefinition` 已经是"纯决策、副作用外置"的形状。
3. **让异常逸出，由外层转事件**：handler 只抛，由一个 inventory 侧的
   `CommandFailureTranslator`（在事务之外）把 `DomainException` 转成失败事件。
   最干净，但需要框架侧支持一个"事务外的失败翻译"扩展点。

无论选哪个，都要：

- **改掉类注释**里 "even before the transaction rolls back" 那句——
  它描述的兜底当前不存在，修复后应改为准确描述新机制；
- 一并处理非 `DomainException` 的逸出路径（见
  [[issue-00068-stock-waits-have-no-deadline-and-can-park-forever]]），
  否则修好了"该回滚的"，"该上报的"还是漏。

## 验证结果

未修。本 issue 由 [[report-00002-scaffold-ddd-review]] 落盘（评审 B2），尚未实施。

## 关联

- [[report-00002-scaffold-ddd-review]]
- [[issue-00068-stock-waits-have-no-deadline-and-can-park-forever]]（同一个 catch 块的另一个方向）
- [[issue-00076-inventory-relies-on-an-upstream-invariant-to-protect-itself]]（同一处的重复加载；方案 2 一并修掉）
- [[issue-00051-aggregates-have-no-optimistic-locking]]（乐观锁本身工作正常）
- [[issue-00027]]（outbox 与聚合同事务的原始保证，本 issue 是它的一个反例场景）
