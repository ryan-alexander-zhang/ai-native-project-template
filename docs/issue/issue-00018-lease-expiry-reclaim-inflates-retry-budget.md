---
id: issue-00018-lease-expiry-reclaim-inflates-retry-budget
type: issue
role: main
status: resolved
parent: plan-00003-durable-process-manager-implementation
---

# 租约过期重认领会消耗重试预算,可能把健康实例误判为耗尽并挂起

## 问题(现状,file:line 为证)

- **等级:Medium**。
- 认领即 `attempts = attempts + 1`:`lease/SkipLockedProcessDialect.java:37,56`、`lease/AtomicUpdateProcessDialect.java:39,62`。
- effect 候选查询包含"`IN_FLIGHT` 且租约过期"分支(`lease/JdbcProcessDialect.java` `CANDIDATE_SQL`),因此一个**只是慢、并未崩溃**
  的 worker 的租约到期后,另一 worker 会重认领并再次 `attempts+1`;或一批 100 条(默认 `batch-size`)串行派发时,批尾条目在
  被派发前租约(默认 30s)就过期而被重认领。
- `attempts` 被非失败原因抬高 → `relay/JdbcProcessEffectRelay.onFailure`(`:145`)与 deadline worker 的 `attempts >= maxAttempts`
  判定提前成立 → 把一个"每次其实都在成功、只是慢"的 effect 推入 `DEAD` 并挂起本无问题的实例。

## 根因(第一性)

1. **观察 vs 期望**:期望"重试预算只被**真正的投递失败**消耗";实际"每次认领(含租约过期重认领)都消耗一次预算"。
2. **最小机制**:`attempts` 语义被放在"认领"这个动作上,而认领既包含首次投递,也包含崩溃恢复、慢 worker 的租约过期重认领——
   后两者不是失败。
3. **真根因**:重试计数应挂在"失败"事件上,而非"认领"动作上(与 Camunda job retry / Temporal 一致——lock 过期让任务重新可领,
   但不消耗 retry)。

## 复现(test-first)

`JdbcProcessEffectRelayTest#anExpiredLeaseReclaimDoesNotConsumeTheRetryBudget`:对一个从未派发失败的 effect 连续做三次
"租约已过期"重认领,断言 `attempts` 仍为 0。修复前每次认领 `attempts+1`,三次后为 3。

## 修复

把 `attempts` 自增从"认领"移到"失败":

1. 两个方言的 effect/deadline 认领 UPDATE 去掉 `attempts = attempts + 1`(只置 `IN_FLIGHT` + 写租约)。
2. effect / deadline store 的 `scheduleRetry` 与 `markDead` 加 `attempts = attempts + 1`(仍由 lease token 围栏)。
3. relay / deadline worker 的 `onFailure` 决策改用 `effect.attempts() + 1`(本次失败后的计数):
   `attempts()+1 >= maxAttempts` 则 `markDead`+挂起,否则 `scheduleRetry`,退避用 `backoff(attempts()+1)`。
   —— 对"每轮都是真实失败"的既有耗尽用例,可观察行为不变(仍在 `maxAttempts` 次失败后 DEAD);仅租约过期重认领不再消耗预算。
4. 更新 `JdbcProcessDialect` 中"认领会 bump attempts"的过时 Javadoc。

## 验证结果

- 新回归测试通过;`exhaustingRetries...`(effect)与 `exhaustingFireRetries...`(deadline)耗尽用例仍在相同轮数后 DEAD+挂起。
- jdbc + starter 模块 test 全绿(含 Testcontainers)。

## 关联

- [[plan-00003-durable-process-manager-implementation]]
