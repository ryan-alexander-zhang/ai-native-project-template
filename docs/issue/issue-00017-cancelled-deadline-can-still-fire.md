---
id: issue-00017-cancelled-deadline-can-still-fire
type: issue
role: main
status: resolved
parent: plan-00003-durable-process-manager-implementation
---

# 已取消的 deadline 仍可能触发;fire 前的 generation/实例校验存在 TOCTOU

## 问题(现状,file:line 为证)

- **等级:Medium**。
- `store/JdbcProcessDeadlineStore.java:55-68` 的 `cancelCurrent` 只匹配 `status = 'PENDING'`,**不取消 `IN_FLIGHT`**,
  且取消**不 bump generation**。
- `deadline/JdbcProcessDeadlineWorker.java:99-113`:`load`、`instances.find`、`currentGeneration` 均在 **fire 事务之外**
  (auto-commit)执行;`fire` 事务(`:116`)内只做 decode/handle/markFired。
- 交错:worker `claimDueDeadlines` 把 deadline 置 `IN_FLIGHT`(自有事务),随后一次业务推进在实例行锁内执行
  `CancelDeadline` → `cancelCurrent` 因行已 `IN_FLIGHT` 而 **no-op**;`fire` 的 `generation == currentGeneration` 守卫
  因 generation 未变而通过 → **照常 markFired 并把超时输入送回 `handle`**,业务收到它以为已取消的超时。
- 同理,generation/实例校验在事务外,重排或生命周期变更在窗口内提交时会被漏检(TOCTOU)。

## 根因(第一性)

1. **观察 vs 期望**:期望"取消对当前代生效(无论 PENDING 还是已被 claim),且 fire 只在仍有效时进行";
   实际"取消只覆盖 PENDING;fire 的有效性判断发生在事务外,claim 之后不再复核状态"。
2. **最小机制**:取消与 fire 各自的可见性/加锁边界没有对齐到同一行锁 —— 取消改的是 deadline 行,fire 的校验却在事务外读、
   之后不复读;`markFired` 只按 lease token 围栏,不看 `status`,于是 `CANCELLED` 会被 `FIRED` 覆盖。
3. **真根因**:fire 的"是否仍应触发"判定必须在 fire 事务内、对相关行加锁后复核;取消必须覆盖当前代的 `IN_FLIGHT`。

## 复现(test-first)

`JdbcProcessDeadlineWorkerTest#aDeadlineCancelledWhileInFlightDoesNotFire`:arm REVIEW → 以**已过期租约**把它置为
`IN_FLIGHT`(模拟已被某 worker claim)→ 业务 `CancelReview`(`CancelDeadline(REVIEW)`)→ `worker.pollOnce()`。
修复前 `cancelCurrent` 对 `IN_FLIGHT` no-op,worker 重认领并触发,实例被超时推进为 `REVIEW_EXPIRED`;
修复后 deadline 变 `CANCELLED`、不再被认领,实例不被超时推进。

## 修复

1. `cancelCurrent`:`WHERE status IN ('PENDING','IN_FLIGHT')`,取消覆盖当前代的已 claim 行。
2. `JdbcProcessDeadlineStore` 增 `statusForUpdate(deadlineId)`(`SELECT status ... FOR UPDATE`,锁 deadline 行)。
3. `JdbcProcessDeadlineWorker.fire` 重构:实例 `findForUpdate` + deadline `statusForUpdate` 复核 + generation 复核 +
   handle + markFired **全在一个 fire 事务内**;仅当仍 `IN_FLIGHT` 且 generation 当前才触发,否则可审计 no-op。
   实例行锁把并发的 `CancelDeadline`/reschedule 串行化,消除 TOCTOU;`markFired` 不再可能覆盖 `CANCELLED`。
   顺带删除永不触发的 `catch (ProcessSuspendedException)` 死代码(handle 挂起时是 park 返回,从不抛该异常)。

## 验证结果

- 新回归测试通过;既有 deadline 用例(fire 推进、superseded no-op、耗尽→DEAD+挂起)不回归。
- jdbc + starter 模块 test 全绿(含 PostgreSQL/MySQL Testcontainers)。

## 关联

- [[plan-00003-durable-process-manager-implementation]]
- 死代码清理与 [[issue-00015-re-suspend-clobbers-resume-lifecycle]] 的 `findForUpdate` 收口同向。
