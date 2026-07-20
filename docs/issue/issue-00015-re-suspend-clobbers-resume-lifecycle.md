---
id: issue-00015-re-suspend-clobbers-resume-lifecycle
type: issue
role: main
status: resolved
parent: plan-00003-durable-process-manager-implementation
---

# 二次挂起把 `resume_lifecycle` 覆盖成 `SUSPENDED`,导致实例永久卡死

## 问题(现状,file:line 为证)

- **等级:High**。
- `ProcessLifecycle.isActive()`(`model/ProcessLifecycle.java:42`)定义为 `!isTerminal()`,**`SUSPENDED` 也算 active**。
- effect relay 与 deadline worker 的耗尽路径用它做守卫:
  - `relay/JdbcProcessEffectRelay.java:150` `if (row != null && row.lifecycle().isActive())`
  - `deadline/JdbcProcessDeadlineWorker.java:145` 同款
- `store/JdbcProcessInstanceStore.java:117` 的 `suspend(id, resumeLifecycle, ...)` **无 revision 守卫**且无条件把 `resume_lifecycle := 传入值`,而 onFailure 传入的是 `row.lifecycle()`(当前值)。
- 当实例**已经** `SUSPENDED`,`row.lifecycle()` 即 `SUSPENDED` → 第二次 `suspend` 把 `resume_lifecycle` 写成 `SUSPENDED`。
- 运维 `operation/JdbcProcessOperations.java:87,117` redrive 时 `resumeLifecycle().orElse(RUNNING)` 取到 `SUSPENDED`
  → `instances.resume(id, SUSPENDED)` 把实例"恢复"回 `SUSPENDED` 且清空挂起元数据 → 无 DEAD 工作再触发 redrive
  → **永久卡死,无恢复路径**。

## 根因(第一性)

1. **观察 vs 期望**:期望"仅当实例正在推进(RUNNING/COMPENSATING)时才可被挂起";实际"任何非终态(含已挂起)都可被再次挂起"。
2. **最小机制**:守卫用了 `isActive()`(= 非终态),把 `SUSPENDED` 也纳入;`suspend()` 又不受乐观并发保护,读到的 `row.lifecycle()`
   在挂起态下就是 `SUSPENDED`,于是被当作 `resume_lifecycle` 回写。
3. **真根因**:①"可被挂起"的判定错误地等同于"非终态";②suspend 的读-改-写没有在同一事务里对实例行加锁,读到的生命周期可能已是挂起态。
   不是 redrive 的错(它只是忠实读取被污染的 `resume_lifecycle`)。

## 触发时序

同一实例同时存在一个失败的 effect 与一个已 `IN_FLIGHT` 的失败 deadline(effect 的候选查询不按生命周期过滤):

1. deadline 在 RUNNING 期触发 → 反复失败 → DEAD → `suspend(resume=RUNNING)`。实例 `SUSPENDED`,`resume_lifecycle=RUNNING`。
2. effect relay 认领同实例 effect(不看生命周期)→ 派发失败耗尽 → DEAD → `find` 读到 `SUSPENDED` → `isActive()` 为真
   → `suspend(resume=SUSPENDED)`。`resume_lifecycle` 被污染为 `SUSPENDED`。

## 复现(test-first)

`JdbcProcessEffectRelayTest#aDeadEffectDoesNotReSuspendAnAlreadySuspendedInstance`:先把实例置于"deadline 已挂起"
状态(`suspend(resume=RUNNING, source=DEADLINE)`),再让一个必失败的 effect 走到 DEAD;断言 `resume_lifecycle` 仍为
`RUNNING`(修复前会变成 `SUSPENDED`)。

## 修复

1. `model/ProcessLifecycle.java` 新增语义方法 `canSuspend()`(仅 `RUNNING`/`COMPENSATING`),供运行时判断"可被挂起"。
2. `relay/JdbcProcessEffectRelay.onFailure` 与 `deadline/JdbcProcessDeadlineWorker.onFailure`:
   - 守卫 `isActive()` → `canSuspend()`;
   - 读实例用 `findForUpdate`(而非 `find`),使"读生命周期 + 挂起"在同一事务内对实例行加锁,与乐观并发主路径不再竞态。

## 验证结果

- 新增回归测试通过;`exhaustingRetriesMovesTheEffectToDeadAndSuspendsTheInstance` 等既有挂起用例不回归。
- jdbc + starter 模块 test 全绿。

## 关联

- [[plan-00003-durable-process-manager-implementation]]
- 同属"suspend/resume 未纳入乐观并发"根因族,`findForUpdate` 一并收口。
