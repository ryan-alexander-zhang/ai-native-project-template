---
id: issue-00104-an-ended-instance-keeps-its-timers-forever
type: issue
status: resolved
parent: report-00003-ddd-library-review-2026-07-29
---

# 终态实例从不回收未决 deadline：永不可领取、永远 DEGRADED、扫描无界膨胀

## 问题（现状，file:line 为证）

- **等级：Critical**（报告 §1 C5）。它不丢数据，但会让健康检查从某一刻起**永久失真**，
  而失真的健康检查等于没有健康检查。
- 决策进入终态（COMPLETED / FAILED / CANCELLED）时，`doHandle` → `ProcessOutcomeWriter.stageEffects`
  只处理本次决策里的 effect，**从不回收该实例已有的 deadline**（旧
  `DefaultProcessRuntime.java:426-540`、旧 `ProcessOutcomeWriter.java:88-126`）。
- 而每次 `start` 在 `instance.max-lifetime` 非 `none` 时都会武装一个兜底 deadline
  （`armMaxLifetimeBackstop`）。**于是每一个正常完成的实例都会留下至少一行 PENDING deadline。**
- 这些行**永远不可能被领取**：claim 查询要求
  `i.lifecycle IN ('RUNNING','COMPENSATING')`（`JdbcProcessDialect.DEADLINE_CANDIDATE_SQL`）。
- 但它们**照样算作到期工作**：`oldestDuePending` 只按 `status = 'PENDING' AND next_attempt_at <= now`
  取 MIN，不看实例生命周期。于是：
  - `ProcessManagerHealthIndicator` 一旦第一行终态残留过了 `oldest-pending-warn`（默认 60s），
    就**永久** DEGRADED，且 `oldestPendingDeadlineSeconds` 单调增长；
  - deadline worker 每轮 poll 都要扫过这些永不可领取的行，代价随历史无界增长。
- `cancelPending` 当时的唯一调用点是操作员 `cancelProcess`——正常完成路径上没有任何回收。

## 根因（第一性）

1. **观察 vs 期望**：期望「实例结束后没有任何遗留的等待」；
   实际「结束只改了实例行，它的 timer 被留在原地，既不可用也不可见地污染 SLI」。
2. **最小机制**：`lifecycle` 与 `deadline.status` 是两份状态，而「实例已结束 ⇒ 它没有活着的 timer」
   这条**跨两张表的不变式没有任何一处代码负责**。claim 查询在**读侧**绕开了它
   （join 上生命周期），SLI 查询没绕，于是两者对同一行给出相反的判断。
   读侧的补丁掩盖了写侧缺失的不变式，只留下 SLI 这一处会说真话。
3. **为何 SLI 是对的**：一行 due 的 PENDING deadline 本来就该被算作积压——
   要修的不是查询，是让终态实例不再留下这种行。
4. **附带的第二个缺口**：deadline worker 的失败处理（`scheduleRetry` / `markDead`）当时只按
   lease token 设防、不看 status，且运行在 fire 事务已回滚之后、任何锁之外。
   于是「fire 因实例进入终态而失败 → 回收把它置为 CANCELLED → 失败处理把它写回 PENDING」
   这条竞态会重新造出一行不可领取的 PENDING，把刚修好的不变式再破坏一次。

## 复现（test-first）

- `JdbcProcessAdvanceContractTest.anEndedInstanceLeavesNoLiveDeadlineBehind`：
  start → ArmDeadline（PENDING）→ Finish（COMPLETED），断言 deadline 为 CANCELLED 且 `completed_at` 已盖章。
- `JdbcProcessDeadlineWorkerTest.aSettledDeadlineIsNotReturnedToTheQueueByALateFailureHandler`：
  claim 后让 cancel 落地，再调 `scheduleRetry` / `markDead`，断言两者都影响 0 行、状态仍为 CANCELLED。
- `JdbcProcessOperationsTest.cancelProcessTerminatesAndCancelsPendingWork` 扩为也断言 deadline 被回收。

## 修复

1. **把不变式放到写侧**：`ProcessOutcomeWriter.stageEffects` 在决策为终态时，于**同一个推进事务**内
   调 `deadlines.cancelLive(instanceId, now)`。effect **不**受影响——一条流程的最后一个
   集成事件正是终态决策 staged 的 effect，必须照常投递；被回收的只有 timer。
2. **`cancelPending` → `cancelLive`，并覆盖 IN_FLIGHT**：语义变了，名字跟着变。
   覆盖 IN_FLIGHT 用的是 `cancelCurrent` 已有的同一道栅栏（worker 在行锁下重读 status 后才 fire），
   而留着它们同样会成为孤儿——claim 只提供活跃实例的 deadline，永不会回收。
3. **失败转移加 status 栅栏**：`scheduleRetry` / `markDead` 的 WHERE 增加 `status = 'IN_FLIGHT'`，
   一次已被结算的 deadline 不可能被晚到的失败处理重新入队。`markDead` 的返回值本就被用来决定
   是否挂起实例，加了栅栏之后这个判断才是诚实的。
4. **fire 先打 FIRED 再推进**：`ProcessDeadlineWorker` 把 `markFired` 从 `handle` 之后移到之前
   （同一事务内，原子性不变）。否则「deadline 触发的这次推进本身让流程结束」时，
   第 1 条的回收会把一个**确实触发过**的 timer 改写成 CANCELLED——审计会说谎。
   顺带地，`markFired` 返回 0 现在意味着租约已不属于自己，直接放弃本次 fire。
5. **终态决策里 schedule deadline 直接报错**：这种 timer 永不可能触发（claim 只看活跃实例），
   而第 1 条的回收会立刻把它结算掉。与其静默丢弃 Definition 的意图，不如响亮失败——
   这是 Definition 的 bug，且它伪装成「一个始终没到的超时」，最难查。

**未做的选择及原因**：没有改 `oldestDuePending` 去 join 生命周期（那是用读侧补丁掩盖写侧缺失的
不变式，SLI 从此再也无法发现同类残留）；没有给四张表加 retention（那是报告 §2 的独立条目，
与本 issue 正交）。

## 验证结果

- 库全量 `mvn verify`（47 模块，含 Testcontainers PG/MySQL 与全部质量门禁）：BUILD SUCCESS。
- 脚手架 `multi-module` 全量 `mvn verify`：BUILD SUCCESS。
- 既有用例 `firesADueDeadlineAndAdvancesTheProcess` 与 `aSupersededGenerationIsAnAuditableNoOp`
  在第 4 条之前会退化为 CANCELLED——它们现在正好把「触发过的 timer 必须记为 FIRED」钉住。

## 关联

- 父：[[report-00003-ddd-library-review-2026-07-29]]（§1 C5）
- 设计：[[design-00004-durable-process-manager-runtime]] §4.7 已同步
- 同批：[[issue-00103-parked-input-replay-is-not-crash-safe]]、
  [[issue-00105-an-advance-conflict-inside-a-joined-transaction-cannot-be-retried]]
- 先例：[[issue-00017-cancelled-deadline-can-still-fire]]（IN_FLIGHT 也要能被 cancel 的栅栏，本次沿用其理由）
