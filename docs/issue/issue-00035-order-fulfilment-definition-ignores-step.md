---
id: issue-00035-order-fulfilment-definition-ignores-step
type: issue
role: main
status: resolved
parent: design-00004-durable-process-manager-runtime
---

# 样例 `OrderFulfilmentDefinition.react()` 只按输入类型分派、忽略当前 Business Step,乱序事实即误推进

> 样例(scaffold)属演示、非设计权威([[samples-not-reference]]);但本缺陷相对该类**自身声明的契约**(javadoc `:36-51` 宣称补偿有序)为真,值得作为样例补强项记录。

## 问题(现状,file:line 为证)

- **等级:High**(样例范畴)。
- `react()`(`OrderFulfilmentDefinition.java:88-127`,scaffold `multi-module/ordering/ordering-process-jdbc`)是纯 `switch (in)`——从不读 `state.step()`,只访问 `state.orderId()`/`state.reservationId()`/`state.paymentDeclineCode()`。runtime 按设计只校验通用 lifecycle(`JdbcProcessRuntime.java:349` `if (!row.lifecycle().canTransitionTo(decision.lifecycle()))`),**不**校验 step,业务乱序规则本应由 Definition 承担,而 Definition 放弃了它。
- 四个乱序/重放场景逐行为证:
  1. **`AWAITING_STOCK + PaymentAuthorized` 直接确认订单**(`:105-107`):无论当前 step,`PaymentAuthorized` 无条件 dispatch `ConfirmOrder`;lifecycle RUNNING→RUNNING 过 `:349` 守卫 → 尚未预留库存的订单被确认。
  2. **未预留就 `PaymentDeclined` → null reservationId 释放库存**(`:108-110`):传 `state.reservationId()`(`start` 时初始化为 `null`,仅 `reserved(...)` 赋值);`RequestStockRelease` record 无校验,null 被静默编码下发;RUNNING→COMPENSATING 合法,无人拦截。
  3. **未拒付就 `StockReleased` → 抛异常、毒消息挂死**(`:111-115`):用 null 的 `state.paymentDeclineCode()` 构造 `PaymentDeclineRef`,其紧凑构造器(`PaymentDeclineRef.java:16-18`)抛 **`DomainException`**(非裸 NPE);`withRetry` 只捕获 `StaleProcessRevisionException | DuplicateKeyException`(`:447`)→ 事务永不提交、at-least-once 无限重投同一毒输入。
  4. **新 messageId 重放旧事实 → 重复产生 effect**:幂等仅按输入 messageId(`transitions.findTransitionIdByInput(ref.instanceId(), cause.messageId())` `:315`;`resolveExistingStart` `:299`),旧业务事实用**新** messageId 重放不算重复 → `react()` 重跑、重复 stage 下游命令。
- **测试缺口**:`ordering-process-jdbc` **无 `src/test` 目录**,无任何 Definition 单测;唯一相关的是别模块的端到端 `PaymentCompensationFlowTest`,只跑 happy/补偿正序,不覆盖任何乱序输入。

## 根因(第一性)

1. **观察 vs 期望**:期望"Definition 按 `(当前 step, 输入)` 决定 advance/ignore/reject/compensate";实际"只按输入类型分派,step 只被写入下一状态、从不用于门控"。
2. **最小机制**:`react()` 的 `switch(in)` 无 step 维度;`OrderFulfilmentState` 的 `Step` 枚举纯粹被"写",从不被"读"以 gate 转移。
3. **真根因**:业务乱序规则缺位。runtime 只保证通用 lifecycle 合法性(设计使然),业务序约束是 Definition 的职责,而本 Definition 完全没实现它——补偿有序只在场景 (3) 里靠 `PaymentDeclineRef` 抛异常**偶然**兜住,场景 (1)(2) 则毫无防护。

## 复现(test-first)

`ordering-process-jdbc` 首次落地 `src/test`:`OrderFulfilmentDefinitionTest`(纯单测,直接喂 `react` 一个手搭的 `ProcessContext`,无 runtime/DB/Spring)。其中三条乱序用例(嵌套类 `OutOfOrderFactsAreIgnored`)复现了本缺陷,修复前分别误确认 / null 释放 / 抛异常:

1. `paymentAuthorizedBeforeReservationDoesNotConfirmTheOrder`:`AWAITING_STOCK` 态喂 `PaymentAuthorized` → 断言 ignore(留在 `AWAITING_STOCK`、无 effect),而非产出 `ConfirmOrder`。
2. `paymentDeclinedBeforeReservationDoesNotReleaseANullHandle`:未预留态喂 `PaymentDeclined` → 断言 ignore,不产出携 null reservationId 的 `RequestStockRelease`。
3. `stockReleasedBeforeADeclineIsIgnoredAndDoesNotThrow`:未拒付态喂 `StockReleased` → 断言 `assertDoesNotThrow` 且 ignore,而非用 null declineCode 构 `PaymentDeclineRef` 抛 `DomainException` 致毒消息无限重投。

## 修复(已实施)

将 `react()` 从纯 `switch(in)` 改为**先按 `state.step()` 分派、再按输入类型**的显式 `(step, input)` 转移表(`OrderFulfilmentDefinition.java`,按 step 拆出 `onAwaitingStock`/`onAwaitingPayment`/`onAwaitingStockRelease`/`onAwaitingOrderConfirmation`/`onAwaitingOrderCancellation`)。每个 `(step, 输入)` 组合恰好落到四种语义之一:

- **advance / compensate / complete**:该 step 期望的事实,推进流程。
- **ignore**:幂等 no-op(保持当前 lifecycle 与 step、无 effect)。因 runtime 是 at-least-once 且把 `react` 抛异常当**毒消息无限重投**(`withRetry` 仅捕获 `StaleProcessRevisionException | DuplicateKeyException`),故一切重复/乱序事实**只 ignore、绝不抛**——正是这一点关掉了三个乱序 bug。
- **reject**:仅 `OrderPlaced`(start-only 输入,正常路径永不进 `react`,抛出不会毒化任何真实重投)。

覆盖完整状态矩阵的单测共 14 例(happy path、两条补偿分支、乱序/重复 ignore、`OrderPlaced` reject、证据 id 来源与互异)。证据 id 一并按 [[issue-00042-process-evidence-ids-fabricated-from-business-keys]] 改为取因果 envelope id。

## 验证结果

- `OrderFulfilmentDefinitionTest`(`ordering-process-jdbc`,新建):14 tests,0 failures/errors。
- 受影响 reactor `mvn -o -pl ordering/ordering-process-jdbc,payment/payment-application -am test` 全绿;全 reactor `mvn -o test-compile` 20 模块 SUCCESS。
- 端到端 `PaymentCompensationFlowTest` / `OrderingFlowTest`(start,Testcontainers PG+Kafka)happy+补偿正序仍绿,证明按 step 门控未回归正常流程。

## 关联

- [[design-00004-durable-process-manager-runtime]]
- [[analysis-00012-multi-module-process-manager-layering]]
- [[issue-00042-process-evidence-ids-fabricated-from-business-keys]]
- [[samples-not-reference]]
