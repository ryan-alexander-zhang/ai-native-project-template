---
id: issue-00082-domain-surface-no-use-case-can-reach
type: issue
role: main
status: open
parent: report-00002-scaffold-ddd-review
---

# 领域建了应用层用不到的能力：ship / ReviewRejected / PaymentOperations.find 三处不可达

## 问题（现状，file:line 为证）

- **等级：Low（不是错误，是**未兑现的模型**；对一个"每个构件都有对应示例"的 scaffold 而言，
  它让能力表与实际可达面之间出现了落差）**。
- 三处领域/端口能力**只被单元测试触达，没有任何用例、命令或端点能到达**：

  **A. 发货整条链路**
  - `Order.ship()`（`Order.java:161-165`）、`OrderShippedEvent`、
    `OrderStatus.SHIPPED`（`:30-31`）、转移规则 `CONFIRMED → SHIPPED`（`Order.java:39`）；
  - 依赖它的 `OrderingErrorCode.RETURN_REQUIRED`（`:48`）与
    `OrderLifecyclePolicy` 里"已发货必须走退货流程"的分支（`:38-41`）；
  - 调用方只有 `OrderLifecycleTransitionsTest:60` 与 `ComplexOrderStateChangeDemoTest:206`。
  - ⇒ 运行中的应用**永远不可能有一张 `SHIPPED` 订单**，因此 `RETURN_REQUIRED` 这条
    精心设计的规则在生产里恒不触发。

  **B. 审核拒绝**
  - `CancellationReason.ReviewRejected`（`CancellationReason.java:56-62`）、
    `CancellationCategory.REVIEW_REJECTED`（`:12`）、
    `OrderLifecyclePolicy.ensureReviewCancellationAllowed`（`:105-116`）；
  - 只有 `ApproveReview` 命令（`ApproveReview.java:18`），**没有 `RejectReview`**。
  - ⇒ 人工审核只能通过，不能拒绝。一张 `AWAITING_REVIEW` 的订单，运维唯一能做的是批准它。

  **C. `PaymentOperations.find`**
  - `PaymentOperations.java:35` 定义、`InMemoryPaymentOperations.java:36-39` 实现、**零调用**。
  - 它本应用于"重投递时重发已记录的结果"，见
    [[issue-00069-payment-idempotency-claim-is-outside-the-transaction]]。

- 对照：README 的能力表（`:66-87`）逐行列出"构件 → 示例 → 验证测试"，
  上述三项都不在表内——**表本身是诚实的**。落差在于领域代码给人的印象是"这些都能用"。

## 根因（第一性）

1. **观察 vs 期望**：期望"领域模型的每个公开能力都有一条从外部到达它的路径"；
   实际"有三处只能从测试到达"。
2. **最小机制**：Java 的可见性与 ArchUnit 都不检查"可达性"。
   `Order.ship()` 是 `public`，编译通过、有测试覆盖、PIT 变异得分也漂亮——
   所有质量门都是绿的，因为它们衡量的是"写下的代码对不对"，不是"写下的代码有没有人用"。
3. **真根因**：状态机是**照着完整业务生命周期**建的（下单→审核→履约→确认→发货→退货），
   而用例是**照着当前 scaffold 想演示的流程**建的（到 CONFIRMED 为止）。
   两者的边界没有被显式对齐，多出来的部分就成了悬空建模。
   `ReviewRejected` 尤其明显：`CancellationCategory.from` 是穷尽 switch（`:16-21`），
   sealed 接口逼着它处理这个分支——**类型系统要求了完整性，应用层没有兑现完整性**。
4. **排除的伪根因**：不是死代码清理问题。这些代码**质量很高**
   （`RETURN_REQUIRED` 那条规则"已发货是退货不是取消"是很好的领域洞察），
   删掉是浪费；问题是它们没有出口。

## 复现（test-first）

用一条覆盖断言把"领域能力必须有用例出口"变成可执行的：

```java
@Test
void everyAggregateStateIsReachableFromSomeUseCase() {
  Set<String> reachable = statusesReachableFromCommands();   // 扫 application 层对 Order 的调用
  Set<String> declared  = Arrays.stream(OrderStatus.values()).map(Enum::name).collect(toSet());
  assertEquals(declared, reachable,
      "领域声明了应用层无法到达的状态：" + Sets.difference(declared, reachable));
}
```

当前会报 `SHIPPED` 不可达。B、C 两项同理可各写一条。
若判定为"有意保留"，则应把断言改成显式白名单——**把默许变成声明**，这本身就是修复的一半。

## 修复

按项分别决定，**每一项都要留下书面结论**：

- **A. 发货**：补一条 `ShipOrder` 命令 + `POST /orders/{id}/ship`（运维动作，与
  `approve-review` 同类），顺带让 `RETURN_REQUIRED` 的 409 有机会被
  `ExceptionContractTest` 覆盖——这是一个便宜且有教学价值的补全。
  或者：把 `ship` / `SHIPPED` / `RETURN_REQUIRED` 移出本 scaffold，
  在 README 的"Not demonstrated here, on purpose"里记一笔。
- **B. 审核拒绝**：补 `RejectReview` 命令（复用已有的
  `CancellationReason.ReviewRejected` 与 `ReviewDecisionRef(decisionId, orderId, false)`——
  注意 `ReviewDecisionRef` 的 `approved` 字段目前恒为 `true`，
  `ApproveReviewHandler.java:44`，它本来就是为这条路径准备的）。
  这一条几乎不需要新代码，只是把已有零件接上。**优先做这条。**
- **C. `find`**：随 [[issue-00069-payment-idempotency-claim-is-outside-the-transaction]] 一起用起来。

## 验证结果

未修。本 issue 由 [[report-00002-scaffold-ddd-review]] 落盘，尚未实施。

## 关联

- [[report-00002-scaffold-ddd-review]]
- [[issue-00069-payment-idempotency-claim-is-outside-the-transaction]]（C 项的正确用法）
- [[issue-00070-ready-for-fulfilment-is-never-persisted]]（另一处"建了但到不了"，那处是状态，这处是行为）
