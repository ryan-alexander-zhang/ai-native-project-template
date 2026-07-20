---
id: issue-00023-operator-redrive-concurrency-and-dead-code
type: issue
role: main
status: resolved
parent: plan-00003-durable-process-manager-implementation
---

# 并发 redrive 未对实例加锁可致假卡死;replay 中的死代码

## 1. 并发 redrive 未串行化(可致实例停在 SUSPENDED 却无 DEAD 工作)

- `operation/JdbcProcessOperations.java:80,110`:`redriveEffect`/`redriveDeadline` 用 `instances.find`(不加锁)读实例,
  再据 `countDead == 0`(`canResume`)决定是否 `resume`。
- 同一实例上两条 DEAD(如一 effect 一 deadline)被两个运维并发 redrive 时,两个事务的"redrive + countDead + resume"
  不在实例行锁下串行,可能各自看到对方仍 DEAD → 都不 resume → 实例停在 `SUSPENDED` 却已无 DEAD 工作(需再次手动触发)。
- **根因**:实例级"读 dead 集合 → 决定 resume"的读-改-写缺乏对实例行的锁,未串行。
- **修复**:两处改用 `instances.findForUpdate`,使整条 redrive 事务在实例行锁下串行,`countDead`+`resume` 原子。

## 2. `replayParkedInputs` 的 `catch (ProcessSuspendedException)` 是死代码

- `operation/JdbcProcessOperations.java:147`:捕获 `ProcessSuspendedException` 并 `break`,但 `runtime.handle` 遇挂起是
  **park 后正常返回**、从不抛该异常(全库无 throw 点;deadline worker 侧的同款已在 [[issue-00017-cancelled-deadline-can-still-fire]] 删除)。
- **修复**:删除该 catch(replay 遇实例被并发挂起时,下一条 handle 自然 park、等下次 redrive——行为不变),并移除无用 import。

## 复现 / 验证

- 并发 redrive 属真实多线程竞态,单线程无法稳定复现;以"实例行锁串行化"论证 + 既有 `JdbcProcessOperationsTest` 顺序 redrive
  恢复/重放用例守护不回归(`findForUpdate` 不改变单线程语义)。
- 死代码删除无行为变化;三模块 test 全绿佐证不回归。

## 关联

- [[issue-00015-re-suspend-clobbers-resume-lifecycle]](同为 suspend/resume 未加锁族)、[[issue-00017-cancelled-deadline-can-still-fire]]。
- [[plan-00003-durable-process-manager-implementation]]
