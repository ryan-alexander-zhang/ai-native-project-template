---
id: issue-00041-payment-missing-business-idempotency-key
type: issue
role: main
status: resolved
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

`ChargePaymentIdempotencyTest`(`payment-application`,新建,纯单测:录制型 `IntegrationEvents` + 真 `InMemoryPaymentOperations`):
- `redeliveringTheSameOperationChargesOnceAndAnnouncesOneAuthorization`:对同一 `paymentOperationId` 连投两条 `ChargePayment`(模拟 at-least-once 重投),断言只发一条 `PaymentAuthorized`。修复前无该键、无去重,必发两条。
- `redeliveringADeclinedOperationAnnouncesOneDeclineOnly`:拒付路径同样只发一条 `PaymentDeclined`。
- `distinctOperationsAreEachCharged`:不同 operationId 仍各自扣款(证明不是把一切都吞掉)。

## 修复(已实施)

1. **契约加业务幂等键**:`PaymentRequested`(`ordering-api`)与 `ChargePayment`(`payment-application`)增 `paymentOperationId` 字段;`RequestPayment`(`ordering-application`)亦增该字段并透传。全链路:process manager → `RequestPayment` → `RequestPaymentHandler` 发 `PaymentRequested` → `PaymentRequestedListener` 发 `ChargePayment`,各调用点一并更新。
2. **process manager 派生稳定 id**:`OrderFulfilmentDefinition.react()` 在 `StockReserved`→`RequestPayment` 这一步用 `context.cause().messageId()`(触发该扣款的事实身份)作 `paymentOperationId`——该值随 effect payload 持久化并确定性重放,故重投复用同一 id。
3. **Payment 侧按 operationId 去重**:新增端口 `PaymentOperations` 与 `ChargePaymentHandler` 依赖它;`recordIfFirst(operationId, decision)` 以 `putIfAbsent` 原子认领,首投认领成功→扣款并发事件,重投认领失败→幂等 no-op、不再扣款/不再发事件。
4. **去重实现的选型(记录)**:采用最轻量而诚实的方案——进程内 `InMemoryPaymentOperations`(`ConcurrentHashMap`),因样例无 payment 数据库;它是真实部署里持久化、带唯一约束的 operations 表(或框架 inbox)的替身,`ChargePaymentHandler` 只依赖 `PaymentOperations` 端口,替换实现不动 handler。与传输层 effectId **互补**(见 [[issue-00032-integration-event-effect-replay-mints-new-eventid]]):effectId 保证同一 effect 重投不铸新传输身份,`paymentOperationId` 保证同一业务扣款经任何路径只生效一次。

## 验证结果

- `ChargePaymentIdempotencyTest`(`payment-application`):3 tests,0 failures/errors。
- 端到端 `PaymentCompensationFlowTest` / `OrderingFlowTest`(start,Testcontainers PG+Kafka)5 tests 全绿:`paymentOperationId` 贯穿真实 outbox→Kafka→inbox 全链路,happy 与补偿路径均只扣一次。
- 全 reactor `mvn -o test-compile` 20 模块 SUCCESS。

## 关联

- [[design-00004-durable-process-manager-runtime]](§13.2 已建模 `paymentOperationId`,本条为其未落地)
- [[issue-00032-integration-event-effect-replay-mints-new-eventid]](传输身份;与业务幂等互补)
- [[issue-00042-process-evidence-ids-fabricated-from-business-keys]](同样是身份来源被业务键顶替)
- [[samples-not-reference]]
