---
id: issue-00134-the-approved-flag-was-never-read
type: issue
role: main
status: open
---

# 审核证据带着 approved 标志，领域层却从来没读过它

2026-07-30 全面评审（P0）。

## 问题

`ReviewDecisionRef`（`ordering-domain/.../ReviewDecisionRef.java:6`）是
`record ReviewDecisionRef(String decisionId, OrderId orderId, boolean approved)`，但领域层
两处消费点都只校验 `belongsTo(orderId)`，从不读 `approved`（已 grep 核实）：

- `Order.approveReview`（`Order.java:154-168`）：传一个 `approved=false` 的 ref 照样批准放行。
- `OrderLifecyclePolicy.ensureReviewCancellationAllowed`（`OrderLifecyclePolicy.java:111-122`）：
  传一个 `approved=true` 的 ref 构造 `CancellationReason.ReviewRejected` 照样取消订单。

语义方向目前完全靠应用层惯例维持（`ApproveReviewHandler.java:52` 传 `true`、
`RejectReviewHandler.java:55` 传 `false`）。

## 根因（第一性）

- 期望：这个模型的核心卖点是"证据可校验"——`Order.cancel` javadoc 明言 "the policy
  guarantees the evidence and current state line up"。
- 分歧机制：校验只覆盖了"证据属于这张订单"，没覆盖"证据的方向与动作一致"。
- 真根因：用一个 boolean 承载了本应是两种类型的事实。`CancellationReason` 已经示范了正确
  手法（sealed + 每种原因携带自己的证据类型），审核证据没有跟上同一模式。

## 复现（先写失败测试）

领域单测：`order.approveReview(new ReviewDecisionRef("d1", id, false))` 断言抛出；
用 `approved=true` 的 ref 构造 `ReviewRejected` 取消断言抛出。修复前两条都安静通过。

## 改法

推荐拆掉 boolean，让非法状态不可表达（与 `CancellationReason` 同一手法）：

```java
public sealed interface ReviewDecisionRef extends OrderEvidenceRef {
  record Approval(String decisionId, OrderId orderId) implements ReviewDecisionRef {}
  record Rejection(String decisionId, OrderId orderId) implements ReviewDecisionRef {}
}
// approveReview(Approval)、ReviewRejected(Rejection) —— 类型即证据方向
```

最小修补（不推荐）：两处消费点各加一行 `approved` 方向检查。

## 验证结果

未修复。
