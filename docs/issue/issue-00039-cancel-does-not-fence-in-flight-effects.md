---
id: issue-00039-cancel-does-not-fence-in-flight-effects
type: issue
role: main
status: open
parent: plan-00003-durable-process-manager-implementation
---

# operator cancel 不 fence 已认领的 IN_FLIGHT effect,cancel 返回后仍可能对外发出副作用

## 问题(现状,file:line 为证)

- **等级:Medium**(语义未明确:cancel 的副作用边界既未在文档/API 明说,实现也未兑现自身声明)。
- `cancelProcess()`(`JdbcProcessOperations.java:175-176`)只取消 **PENDING**:
  `effects.cancelPending(ref.instanceId(), clock.instant())` + `deadlines.cancelPending(...)`。
  `cancelPending` 的 SQL 仅把 `status = PENDING → CANCELLED`,谓词 `WHERE instance_id = ? AND status = ?`
  (`JdbcProcessEffectStore.java:180-183`)。已被 relay 认领、置为 **IN_FLIGHT** 的行完全不动。
- `deliver()`(`JdbcProcessEffectRelay.java:146-172`)在对外 dispatch **之前不查**实例 lifecycle:
  `effects.load(effectId)`(:147)→ decode →
  `dispatchers.dispatch(...)`(:162)→ `effects.markDelivered(effectId, leaseToken, ...)`(:170),
  全程无 CANCELLED / lifecycle 检查。`pollOnce` 先把一批 effect 认领为 IN_FLIGHT,再循环逐条 `deliver`;
  若 `cancelProcess` 恰在这个窗口内提交,已认领的扣款 / 库存命令仍会照常对外发出。
- **语义冲突**:design-00004:834-835 与本类 javadoc(`JdbcProcessEffectRelay.java:34-36` 一带)都声称
  cancel "只终止协调器、取消**尚未派发**的 effect / deadline、不发补偿"。而 IN_FLIGHT-但-未-`markDelivered`
  的 effect 语义上正属"尚未派发",实现并未兑现这条承诺。

## 根因(第一性)

1. **观察 vs 期望**:期望 "cancel 返回后不再产生新的对外副作用"(或至少不动尚未派发的 effect);
   实际 "cancel 只清 PENDING,已认领为 IN_FLIGHT 的 effect 照常投递"。
2. **最小机制**:cancel 的取消谓词只匹配 `status = PENDING`(`JdbcProcessEffectStore.java:180-183`),
   而投递侧 `deliver()`(`JdbcProcessEffectRelay.java:146-172`)在 dispatch 前不复查实例 lifecycle。
   两者之间没有任何 fencing / epoch 把 "cancel 已发生" 传达给正在 in-flight 的投递。
3. **真根因**:cancel 与 effect 投递之间缺一个 **cancellation 屏障**——要么把 "cancel 是 best-effort、
   IN_FLIGHT 仍会投递" 明确写进文档/API,要么引入 cancellation epoch/fencing 并在 dispatch 前复查实例状态。
   当前实现踩在两者之间:既宣称取消"尚未派发"的 effect,又漏掉了 IN_FLIGHT 这段窗口。

## 复现(test-first)

提议一个并发回归测试:令某个 effect 处于 **IN_FLIGHT**(已被 `pollOnce` 认领、尚未 `deliver`),
在该窗口内对其实例调用 `cancelProcess(...)`,随后放行投递;断言该 effect **不再**对外 `dispatch`
(可用一个记账式 `EffectDispatcher` 桩计数)。修复前该 effect 仍会被 dispatch → 测试失败,复现问题。

## 修复

需先明确语义,二选一并落到文档 + API + 测试:

1. **best-effort(维持现状语义)**:在 `cancelProcess` 的 javadoc、`ProcessOperations` API 文档与
   design-00004 中明确 "cancel 只取消尚未认领(PENDING)的 effect;已认领为 IN_FLIGHT 的 effect 仍会投递",
   并同步修正当前声称取消"尚未派发"的措辞,消除自相矛盾。
2. **strict(返回后不得再产生新副作用)**:引入 cancellation epoch / fencing —— `cancelProcess` 记录取消位点,
   `deliver()` 在 dispatch 前复查实例是否已 CANCELLED,若是则跳过对外投递并把该 effect 记为取消 / 作废。

## 关联

- [[issue-00017-cancelled-deadline-can-still-fire]] —— 同属"取消后仍可触发副作用"类缺陷(deadline 侧)。
- [[design-00004-durable-process-manager-runtime]] —— cancel 语义的设计出处(:834-835)。
- [[plan-00003-durable-process-manager-implementation]]
