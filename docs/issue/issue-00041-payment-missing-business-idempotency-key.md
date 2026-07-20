---
id: issue-00041-payment-missing-business-idempotency-key
type: issue
role: main
status: open
parent: design-00004-durable-process-manager-runtime
---

# Payment 缺业务幂等键 `paymentOperationId`,样例无法证明扣款安全

## 问题(现状,file:line 为证)

- **等级:样例范畴**(design-vs-code 背离;属 [[samples-not-reference]] 演示树,但确为相对设计的真实缺口)。
- 设计明确要求以聚合持有的**业务操作 id** 作幂等键:
  - `docs/design/design-00004-durable-process-manager-runtime.md:283-285`:"不可逆业务动作(**扣款**、扣库存)仍必须由聚合持有的**业务操作 id**(如 `paymentOperationId`,§13.2 已建模)自行幂等——即便该操作经另一路径触达也不重复。"
  - `:1269`:"`paymentOperationId` 作为**业务幂等键**放进事件 payload,Payment 服务据此幂等(与传输层 effectId 互补)。"
  - 参考态 `:1246` 亦建模 `paymentOperationId/paymentDeclineRef`。
- 但样例契约**不带**该键:
  - `PaymentRequested.java:13` = `record PaymentRequested(String orderId, long amountMinor, String currency)`。
  - `ChargePayment.java:12-15` 仅 `orderId`/`amountMinor`/`currency`。
  - 全 payment 模块 grep `paymentOperationId`/`operationId`/`idempoten` **零命中**(仅出现在 docs 与无关的 web 层 `Idempotency-Key` 机制)。
- Payment 侧**无聚合/repository 去重**:domain 只有无状态的 `AuthorizationPolicy`(纯函数,金额 >`50_000` 拒付)与 `PaymentDecision` 值类型;application `ChargePaymentHandler.java:31-40` 算完 decision 立即 `integrationEvents.publish(...)`,不持状态、不去重。故重投同一 `ChargePayment` 会**重复扣款**。

## 根因(第一性)

1. **观察 vs 期望**:期望"同一次业务扣款经任何路径(重投/多入口)触达 Payment 只生效一次";实际"Payment 是无状态转换,重投即重复扣款"。
2. **最小机制**:传输层 effectId 只能保证同一 effect 的重投不产生新传输身份(见 [[issue-00032-integration-event-effect-replay-mints-new-eventid]]),**无法**代替业务幂等——设计已将二者定义为**互补、非二选一**(`:283-285`)。样例却既无 `paymentOperationId` 载荷,也无按该键去重的聚合。
3. **真根因**:业务幂等键 `paymentOperationId`(设计称"§13.2 已建模")在样例契约与 Payment 聚合中**双双缺席**。
   - 排除的表象:这不是 gateway 桩"故意无状态"造成的偶然——`AuthorizationPolicy.java:5-7` 明说自己是"deterministic stand-in for a real gateway",无状态是刻意的;但**缺业务幂等键**是相对设计的真实背离,与桩是否有状态无关。

## 复现(test-first)

提议的失败测试:对 Payment 连投两条**同一业务操作**的 `ChargePayment`(相同 `paymentOperationId`,模拟 at-least-once 重投),断言只产生一次扣款/一条 `PaymentAuthorized`。今日因契约无该键、Payment 侧无处去重,必扣款两次 → 测试失败。修复后应只扣一次。

## 修复

1. 给 `PaymentRequested` / `ChargePayment` 增 `paymentOperationId` 字段,由 process manager 用**稳定的 effect/事实身份**派生(与 [[issue-00042-process-evidence-ids-fabricated-from-business-keys]] 一并纠正证据身份)。
2. Payment 侧引入按 `paymentOperationId` 去重的**聚合或 operation repository**:首次处理落库该 operation,重投命中即幂等返回既有结果,不再触发扣款。

## 关联

- [[design-00004-durable-process-manager-runtime]](§13.2 已建模 `paymentOperationId`,本条为其未落地)
- [[issue-00032-integration-event-effect-replay-mints-new-eventid]](传输身份;与业务幂等互补)
- [[issue-00042-process-evidence-ids-fabricated-from-business-keys]](同样是身份来源被业务键顶替)
- [[samples-not-reference]]
