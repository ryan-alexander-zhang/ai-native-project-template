---
id: issue-00039-cancel-does-not-fence-in-flight-effects
type: issue
role: main
status: resolved
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

## 复现(已落地)

`JdbcProcessEffectRelayTest#doesNotDispatchAnInFlightEffectWhoseInstanceWasCancelled`:令 start 的 command effect
被认领为 **IN_FLIGHT**(`dialect.claimDueEffects` 打上租约 `token-A`),随后把实例 lifecycle 直接置 `CANCELLED`
(模拟 `cancelProcess` 提交——`cancelPending` 只动 PENDING,IN_FLIGHT 那行原样留下),再让租约过期触发 relay 重认领并
`pollOnce`。断言:`pollOnce` 返回 0、记账式 `RecordingCommandBus` 未收到任何 command、effect 落到终态 `CANCELLED`。
修复前 relay 会解码并 `dispatch`(bus 收到 1 条、effect 变 `DELIVERED`)→ 测试失败,复现"cancel 返回后仍对外发副作用"。

## 修复(已实施)

采用 strict 语义("cancel 返回后不再产生新的对外副作用"),用实例 lifecycle 作 fencing、无需新增 epoch 列:

1. **`JdbcProcessEffectRelay.deliver()`** 在对外 dispatch **之前**加 cancellation fence:`instances.find(effect.instanceId())`
   复查 owning 实例,若 lifecycle 为终态 `CANCELLED`,则跳过 dispatch,调用 `effects.markCancelled(effectId, leaseToken, ...)`
   把该 effect 记为终态 `CANCELLED` 并 `return false`——扣款/库存命令永不发出。
2. **`JdbcProcessEffectStore` 新增 `markCancelled(effectId, leaseToken, now)`**:把行置 `CANCELLED` 并清租约,谓词
   `WHERE effect_id = ? AND lease_token = ?`——与 `markDelivered` 一样由**租约令牌 fence**,只有当前 owner 能作废,清 stale
   owner 无法误伤。
3. **文档**:修正 `JdbcProcessOperations` 类/`cancelProcess` javadoc 与 `JdbcProcessEffectRelay` 类 javadoc,明确 cancel
   现在也 fence 已认领为 IN_FLIGHT 的 effect(relay 派发前复查),兑现"cancel 返回后无新副作用",消除原先自相矛盾的措辞。

## 验证结果

- 新回归测试通过;relay 既有 11 条(at-least-once 重投、租约 fence、DEAD+suspend 等)不回归——非 CANCELLED 实例的 effect 仍照常投递。
- `mvn -o -pl aipersimmon-ddd-process-manager-jdbc -am test` 与 `-pl ...-spring-boot-starter -am test` 均 BUILD SUCCESS,失败/错误计数为 0。

## 关联

- [[issue-00017-cancelled-deadline-can-still-fire]] —— 同属"取消后仍可触发副作用"类缺陷(deadline 侧)。
- [[design-00004-durable-process-manager-runtime]] —— cancel 语义的设计出处(:834-835)。
- [[plan-00003-durable-process-manager-implementation]]
