---
id: issue-00035-order-fulfilment-definition-ignores-step
type: issue
role: main
status: open
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

提议 Definition 单测(尚未落地,应随该模块首个 `src/test` 一并建立):

1. `AWAITING_STOCK` 态喂 `PaymentAuthorized` → 断言 ignore/park,而非产出 `ConfirmOrder`。
2. 未预留态喂 `PaymentDeclined` → 断言不产出携 null reservationId 的 `RequestStockRelease`。
3. 未拒付态喂 `StockReleased` → 断言 reject/ignore,而非抛 `DomainException` 致重投挂死。

今日三者均失败(误确认 / null 释放 / 抛异常)。

## 修复

改成显式 `(currentStep, input)` 转移表(提议,未实施):为每个 `(step, 输入)` 组合定义 advance / ignore / reject / compensate 语义;并优先补齐 `ordering-process-jdbc` 覆盖完整状态矩阵的单元测试。证据 id 一并按 [[issue-00042-process-evidence-ids-fabricated-from-business-keys]] 修正。

## 关联

- [[design-00004-durable-process-manager-runtime]]
- [[analysis-00012-multi-module-process-manager-layering]]
- [[issue-00042-process-evidence-ids-fabricated-from-business-keys]]
- [[samples-not-reference]]
