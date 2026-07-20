---
id: issue-00036-max-lifetime-backstop-overwrites-definition-deadline
type: issue
role: main
status: resolved
parent: plan-00003-durable-process-manager-implementation
---

# 默认 max-lifetime backstop 覆盖 Definition 在 start 中对保留 deadline 的调度

## 问题(现状,file:line 为证)

- **等级:Medium**(触发条件为 edge:仅当 Definition 在 `start` 阶段操作保留名 `MaxLifetimeExceeded.DEADLINE_NAME` 时才显现)。
- `doStart()`(`JdbcProcessRuntime.java:262-274`)的执行顺序是:`callStart(definition, input, context)`(`:262`,运行 Definition)→ `appendTransition(...)`(`:272`)→ `stageEffects(...)`(`:273`,先应用 Definition 产出的 `ScheduleDeadline`/`CancelDeadline` effects)→ `armMaxLifetimeBackstop(...)`(`:274`,**随后**无条件挂上默认 `aipersimmon.max-lifetime`)。
- `armMaxLifetimeBackstop`(`:287-294`)只守两条:`maxLifetime.isEmpty() || !decision.lifecycle().isActive()` 时才返回(`:289`)。它**不**检查保留名 `MaxLifetimeExceeded.DEADLINE_NAME` 是否已被本次 Definition decision 动过,直接
  `scheduleDeadline(ref, new ScheduleDeadline(MaxLifetimeExceeded.DEADLINE_NAME, now.plus(maxLifetime.get()), new MaxLifetimeExceeded()), cause, now)`(`:292-293`)。
- `scheduleDeadline`(`:424-425`)取 `nextGeneration = MAX(generation)+1`;而 deadline store 只烧**最高**generation,较低 generation 迟到触发即 no-op(`JdbcProcessDeadlineStore.java:14-15,130`)。因此若 Definition 已在 start 中对保留名 schedule/cancel,backstop 随后用**更高 generation + 默认 dueAt** 覆盖它——Definition 写入的 generation 立即变陈旧(其 `CancelDeadline` 亦被重新 arm),业务决定被盖。

## 根因(第一性)

1. **观察 vs 期望**:期望"Definition 在 start 中对 max-lifetime 保留名的调度(延长/缩短/取消)是最终决定";实际"runtime 随后无条件挂默认 backstop,以更高 generation 盖掉它"。
2. **最小机制**:backstop 挂在 `stageEffects` **之后**且**无条件**(`:274` + `:289` 的守卫不含保留名探测),而 generation 单调递增 + "只烧最高 generation"的语义使后写者恒赢。
3. **真根因**:默认 backstop 与 Definition 对**同一个保留名**的调度之间缺少让位规则——要么顺序错(应先挂默认、再让 Definition 覆盖),要么缺条件(backstop 应在保留名已被本次 decision 操作时退避)。
4. **措辞误导**:`armMaxLifetimeBackstop` 的 javadoc 把"definition 也可 reschedule 保留名、只是 bump generation"描述得像无害,实则 backstop 会再 bump 一次把它盖掉。

## 复现(已落地)

新增 `JdbcProcessMaxLifetimeReservedDeadlineTest`(配 30 天 `maxLifetime`,自带一个在 `start` 中操作保留名的 Definition):

- `aDefinitionReschedulingTheReservedNameInStartWinsOverTheDefaultBackstop`:Definition 在 start 中对
  `MaxLifetimeExceeded.DEADLINE_NAME` 下达自定义 dueAt(T0+5 天)的 `ScheduleDeadline`;断言保留名 deadline **只有一行**且其
  `due_at` 逐字等于自定义 dueAt,而非默认 T0+30 天。修复前会有两行,最高 generation(backstop)持默认 dueAt。
- `aDefinitionCancellingTheReservedNameInStartWinsOverTheDefaultBackstop`:Definition 在 start 中对保留名下达
  `CancelDeadline`;断言保留名 deadline 行数为 **0**(取消生效、默认 backstop 不再补挂)。修复前会剩一行默认 backstop。

## 修复(已实施)

采用方案 2(条件退避):

1. `armMaxLifetimeBackstop` 在挂默认 backstop 前,先调新增的私有静态 `decisionTouchesReservedDeadline(decision)`——
   遍历 decision effects,若已有 `ScheduleDeadline`/`CancelDeadline` 的 `name` 等于 `MaxLifetimeExceeded.DEADLINE_NAME`
   即返回 `true`,此时 backstop 直接退避(`return`),不再以更高 generation 覆盖 Definition 的决定。
2. 修正 `armMaxLifetimeBackstop` 的 javadoc:去掉"definition 也可 reschedule、只是 bump generation"的误导措辞,明确
   "Definition 在 start 中调度/取消保留名即拥有该 timer,默认 backstop 让位"。

## 验证结果

- 两个新回归测试通过;`JdbcProcessMaxLifetimeTest` 既有四条(未操作保留名时仍正常挂默认 backstop、firing 让 Definition 决定等)不回归。
- `mvn -o -pl aipersimmon-ddd-process-manager-jdbc -am test` 与 `-pl ...-spring-boot-starter -am test` 均 BUILD SUCCESS,失败/错误计数为 0。

## 关联

- [[issue-00017-cancelled-deadline-can-still-fire]]
- [[plan-00003-durable-process-manager-implementation]]
