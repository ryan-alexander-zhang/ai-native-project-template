---
id: issue-00042-process-evidence-ids-fabricated-from-business-keys
type: issue
role: main
status: resolved
parent: design-00004-durable-process-manager-runtime
---

# 样例进程用业务键伪造证据 ref,弃用可得的因果 envelope id

## 问题(现状,file:line 为证)

- **等级:样例范畴**(scaffold 演示,非库契约;见 [[samples-not-reference]])。但它示范了一种会削弱审计与幂等的坏模式,值得单列。
- `aipersimmon-ddd-scaffold/multi-module/ordering/ordering-process-jdbc/.../OrderFulfilmentDefinition.react()`
  用**业务键**充当证据 ref 的身份,而非因果 envelope 的 `messageId`。逐字段核对各 `record` 首参:
  - `:98-99` `new ReservationFailureRef(orderId, new OrderId(orderId), failed.code(), failed.reason())`
    ——首字段是 `String failureId`(`ReservationFailureRef.java:11`)→ **failureId = orderId**。
  - `:113-114` `new PaymentDeclineRef(orderId, id, state.paymentDeclineCode())`
    ——首字段 `String declineId`(`PaymentDeclineRef.java:6`)→ **declineId = orderId**。
  - `:115` `new StockReleaseRef(released.reservationId(), id)`
    ——首字段 `String releaseId`(`StockReleaseRef.java:11`)→ **releaseId = reservationId**。
- 结果:同一 `orderId` 同时充当 `failureId` 与 `declineId`,`reservationId` 充当 `releaseId`——这些证据 id
  彼此碰撞、并非独立的证据身份,无法区分"哪一次事实/哪一条 envelope"产生了该证据。

## 根因(第一性)

1. **观察 vs 期望**:期望每条证据 ref 携带一个唯一、可追溯到具体因果消息的身份;实际它们复用业务键
   (orderId/reservationId),互相碰撞。
2. **最小机制**:真实、唯一的 envelope id **就在方法签名里却未被读取**。`react(...)` 收 `ProcessContext context`
   (`OrderFulfilmentDefinition.java:90`),`ProcessContext.java:37` 暴露 `CommandContext cause`,
   `CommandContext.java:28-29` 为 `record CommandContext(String messageId, ...)`——即 `context.cause().messageId()`
   可编译、给出因果消息的唯一 id。但 `react()` 构造证据 ref 时只读 `state`/`in`,从不读 `context`。
3. **真根因**:样例把"业务标识"误当"证据标识"。二者语义不同——业务键标识*聚合*,证据 id 标识*产生该证据的那一次事件*。
   这不是"找不到唯一 id",而是"有唯一 id 却没用"。

## 复现(test-first)

`OrderFulfilmentDefinitionTest`(`ordering-process-jdbc`,新建,与 [[issue-00035-order-fulfilment-definition-ignores-step]] 同一测试类):
- `reservationFailedCompensatesWithFailureEvidenceIdFromTheCause`:断言 `ReservationFailureRef.failureId == context.cause().messageId()`(而非 orderId)。
- `stockReleasedCancelsWithTwoDistinctEvidenceIds`:断言 `PaymentDeclineRef.declineId` 取自记住的拒付事件 id、`StockReleaseRef.releaseId` 取自当前 stock-released 事件 id,且 `assertNotEquals` 二者互异。
- `theThreeEvidenceIdsAcrossAFlowAreAllDistinct`:把三条证据 id 收进 `Set`,断言 size==3。

修复前:failureId==declineId==orderId、releaseId==reservationId,断言必败。

## 修复(已实施)

`react()` 现读 `context`(此前完全忽略),证据 ref 一律用**因果 envelope id** 而非业务键:
- `ReservationFailureRef` 首参 `failureId = context.cause().messageId()`(触发 `StockReservationFailed` 那条 envelope)。
- `StockReleaseRef` 首参 `releaseId = context.cause().messageId()`(当前 `StockReleased` 那条 envelope)。
- `PaymentDeclineRef` 首参 `declineId`:因它与 `StockReleaseRef` 在**同一** `react()`(`StockReleased` 分支)里构造、共享同一 `context.cause()`,若也取当前 cause 会与 releaseId 碰撞;故在 `PaymentDeclined` 分支把该拒付事件的 `messageId` 存入新增的 `OrderFulfilmentState.paymentDeclineEvidenceId`,到 `StockReleased` 时用它——两条证据遂各指其真实事件、互不碰撞(state codec 与其一并加一字段)。

修复后审计链、证据引用与业务幂等各自成立、可区分。

## 验证结果

- `OrderFulfilmentDefinitionTest`(`ordering-process-jdbc`):14 tests(含上述 3 条证据身份用例),0 failures/errors。
- 端到端 `PaymentCompensationFlowTest`(start,Testcontainers)绿:补偿路径经真实 CancelOrder 编解码(`OrderFulfilmentCodecs` 已随之处理 evidence id 往返)后仍以 `PAYMENT_DECLINED` 类别落地。
- 全 reactor `mvn -o test-compile` 20 模块 SUCCESS。

## 关联

- [[issue-00035-order-fulfilment-definition-ignores-step]] —— 同一 `react()` 的另一处样例缺陷(忽略当前 step)。
- [[design-00004-durable-process-manager-runtime]] —— 运行时的稳定身份契约(effectId/messageId)语境。
- [[samples-not-reference]] —— scaffold 为演示,非设计权威。
