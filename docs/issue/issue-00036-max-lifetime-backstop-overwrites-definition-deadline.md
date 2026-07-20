---
id: issue-00036-max-lifetime-backstop-overwrites-definition-deadline
type: issue
role: main
status: open
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

## 复现(test-first)

提议单测(尚未落地):让一个 Definition 在 `start` 中对 `MaxLifetimeExceeded.DEADLINE_NAME` 下达 `CancelDeadline`(或改期到自定义 dueAt),推进 start 后查询该实例保留名的当前有效 deadline;断言其为 Definition 的决定(已取消 / 自定义 dueAt),而非默认 `aipersimmon.max-lifetime`。今日该断言会失败——默认 backstop 以更高 generation 覆盖。

## 修复

二选一(提议,未实施):

1. **调序**:先 `armMaxLifetimeBackstop`(挂默认),再 `stageEffects` 应用 Definition effects,使 Definition 对保留名的调度以更高 generation 覆盖默认值。
2. **条件退避**:`armMaxLifetimeBackstop` 前先探测本次 decision 是否已对 `MaxLifetimeExceeded.DEADLINE_NAME` 产出 `ScheduleDeadline`/`CancelDeadline`,已操作则跳过默认调度。

任一方案都应补上"Definition 对保留名的调度胜出"的回归测试,并修正 `armMaxLifetimeBackstop` 的 javadoc。

## 关联

- [[issue-00017-cancelled-deadline-can-still-fire]]
- [[plan-00003-durable-process-manager-implementation]]
