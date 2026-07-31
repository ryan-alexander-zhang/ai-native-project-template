---
id: issue-00144-a-cancelled-order-keeps-its-authorization-hold
type: issue
role: main
status: open
---

# 取消与授权赛跑之后，已取消订单的授权 hold 无人 void

2026-07-30 全面评审（P1；工作量最大，可最后做）。

## 问题

`OrderFulfilmentDefinition.java:376-387`：`AWAITING_PAYMENT` 收到 `OrderCancelled` → 转
`AWAITING_STOCK_RELEASE_ORDER_CANCELLED`，但 `RequestPayment` effect 此前已派发；:459-480
该步骤只处理 `StockReleased`/`StockReleaseTimedOut`，迟到的 `PaymentAuthorized` 落入
`ignore`。支付上下文可能已对一张已取消的订单完成授权，`PaymentOperations` 里躺着一条
`Authorized` 决策，没有任何东西去 void 它。

对照：库存路径**有**对称补偿（`stock-reserved-for-cancelled-order` → 立即
`RequestStockRelease`，:324-335），支付路径没有。

## 根因（第一性）

- 期望：saga 的每个已派发的外部动作都有对应补偿路径（补偿完备性）。
- 分歧机制：`PaymentAuthorized` 在两个 `*_ORDER_CANCELLED` 步骤被 `ignore`——ignore 语义
  本是为重复/乱序输入设计的，这里吞掉的却是一个需要动作的事实。
- 真根因：补偿路径按"上游拒绝我"设计（decline/timeout 都有），漏掉了"上游答应了但我已经
  不要了"这一格。

减刑因素（所以不是 CRITICAL）：scaffold 明文只演示 authorize 不演示 capture
（`AuthorizePayment.java:14-16`），真实卡授权 hold 会自然过期；且触发需要三方竞态。
流程图 javadoc（:44-54）对该分支只字未提——即便决定接受风险，也必须先把它写成已声明的
取舍。

## 复现（先写失败测试）

流程测试：`AWAITING_PAYMENT` 收 `OrderCancelled` 后再投 `PaymentAuthorized`，断言产生
void 类 effect（或按接受风险的决定断言 javadoc 已声明）。当前被 ignore。

## 改法

在 `AWAITING_STOCK_RELEASE_ORDER_CANCELLED` 与 `AWAITING_STOCK_ORDER_CANCELLED` 收到
`PaymentAuthorized` 时派发 `RequestPaymentVoid`（镜像 `RequestPayment`：ordering 内部命令 →
`PaymentVoidRequested` 集成事件 → payment 按 `paymentOperationId` 幂等 void）。最低限度：
流程图 javadoc 写明"授权可能悬挂，依赖 hold 过期"。

## 验证结果

未修复。
