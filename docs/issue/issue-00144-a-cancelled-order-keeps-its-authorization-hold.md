---
id: issue-00144-a-cancelled-order-keeps-its-authorization-hold
type: issue
status: resolved
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

2026-07-31 修复。**实现比改法草案更强：主动 void，而非等迟到的 `PaymentAuthorized`。**
草案（在 `*_ORDER_CANCELLED` 步骤响应迟到的 Authorized）有一个它自己修不掉的窗口——
流程可能先到终态（timeout 路径尤然），而 **runtime 对终态实例不再 react**，迟到的
Authorized 依旧石沉大海、hold 依旧无主。改为：**放弃等支付的那个决策自己派发
`RequestPaymentVoid`**（两处：`AWAITING_PAYMENT` 收 `PaymentTimedOut` / 收 `OrderCancelled`；
真 decline 不派——decline 是 payment 已记录的决定，永不可能事后 authorize）。顺带说明
草案点名的 `AWAITING_STOCK_ORDER_CANCELLED` 实为不可达输入：该路径从未派发过
`RequestPayment`，Authorized 结构上到不了那里。

- **ordering**：`OrderFulfilmentState` 增 `paymentOperationId`（stock-reserved 决策记下——
  到放弃时因果信封是定时器/取消事件，id 从当前 cause 推不出来，只能走状态）；新命令
  `RequestPaymentVoid` + handler → 新集成事件 `PaymentVoidRequested`（api record 含
  issue-00143 标准的紧凑构造器校验；**无回执事件**，在案：没人等 void 的答案，回执纪律
  只属于有流程在等的出口）；codecs 注册 + `declaredPayloads` 补一项；流程图 javadoc 更新。
- **payment**：`PaymentDecision` 增 `Voided`（对未记录操作 = **预先拒绝**，对 Authorized =
  释放 hold，均为终态）；`PaymentOperations.markVoided`（唯一被批准的 UPDATE：
  `WHERE outcome='AUTHORIZED'` 谓词即幂等）；`VoidPaymentHandler` 三分支（空→record Voided、
  Authorized→markVoided、Declined/Voided→落空）；**竞态由操作行主键原子裁决**——与两次并发
  授权同一套机制；`AuthorizePaymentHandler` 收录 Voided 档：不授权、照回执
  `PaymentDeclined(code="payment.voided")`（回执契约按次交付，流程侧本来就 ignore）。
- **测试**：定义级 4 条新测（timeout void、cancel void、**decline 不 void**、operationId
  经状态存续）+ 既有"timeout 同 decline"测试的断言修正（多出的 void 是特意的）；payment 侧
  `PaymentVoidRaceTest` 4 条全交错（void 先到预拒 + 授权仍有回执、void 后到释放 hold、重投
  落空、decline 后落空）；e2e：`PaymentTimeoutFlowTest` 增断言——真出箱→Kafka→inbox→
  payment 操作行 `VOIDED` 全程走通。**负向对照真跑到**：临时移除两处 void 派发（其余可编译）
  实跑 e2e——`expected: <1> but was: <0>` 超时 40.8s；恢复后 10.6s 绿，恢复以 grep 零残留
  确认。

验证：ordering-process / payment 全模块绿；scaffold `clean test -pl start -am` 验收
BUILD SUCCESS（90 测）。库侧无改动。
