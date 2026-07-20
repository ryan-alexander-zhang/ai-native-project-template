---
id: issue-00037-parked-input-replay-order-non-monotonic
type: issue
role: main
status: open
parent: plan-00003-durable-process-manager-implementation
---

# PARKED input 重放顺序非确定:排序键(`created_at`+随机 UUID)无每实例单调性

## 问题(现状,file:line 为证)

- **等级:Medium**(触发条件:同一实例 suspension 期间,≥2 个 input 在**同一毫秒**内被 park)。
- `findParkedInputs()`(`store/JdbcProcessTransitionStore.java:139-151`)对 PARKED input 的取回按
  `ORDER BY created_at, transition_id`(`:144`)排序,并以此顺序 resume replay。
- 该排序键的两列都不保证每实例单调:
  - `created_at` 为 `DATETIME(3)`——**毫秒精度**(MySQL DDL `.../mysql/V1__aipersimmon_process_manager.sql:48`,
    文件头注释亦称 "DATETIME(3) keeps millisecond precision")。同毫秒到达即并列。
  - `transition_id` 为**随机 UUIDv4**:park 写行用 `parkedId = idGenerator.get()`(`runtime/JdbcProcessRuntime.java:323`),
    默认生成器 `() -> UUID.randomUUID().toString()`。作为 tie-break 它是随机的,不反映到达顺序。
- transition 表**没有每实例单调序列列**:`mysql/V1` 的列为 `transition_id / instance_id / input_message_id / ... /
  created_at`,无 `seq`。**只有 effect 表**带 `seq BIGINT`(`:58`,经 `effects.nextSeq()` 填充);transition / parked
  input 没有对应列可用。
- 结果:同毫秒 park 的两个 input,replay 顺序退化为随机 UUID 的字典序 → **非确定**。这会影响 timeline 呈现、
  latest transition 判定,以及 PARKED input 的 resume 重放次序。

## 根因(第一性)

1. **观察 vs 期望**:期望"PARKED input 按到达/因果顺序重放";实际"同 `created_at` 的两条按随机 `transition_id`
   任意排序"。
2. **最小机制**:重放顺序不变式被编码在 `(created_at, transition_id)` 上,但 `created_at` 毫秒精度不保证严格单调、
   `transition_id` 是随机 UUID——**没有任何一个每实例单调的排序键**。
3. **真根因**:transition 侧缺一个每实例单调序列列。这与 effect 侧 [[issue-00016-per-instance-effect-ordering-not-guaranteed]]
   同源——顺序约束必须落在可靠的单调键上,不能依赖墙钟或随机 id。effect 表的 `seq`(`MAX(seq)+1`、实例行锁内)是现成先例。
   排除的**非**根因:不是 `created_at` 精度不够(即便纳秒也有并列与回拨风险),而是排序键选错。

## 复现(test-first)

提议回归测试(尚未编写):同一实例进入 suspension 后,在 `Clock.fixed`(令两次 park 的 `created_at` 完全相等)下
连续 park 两个不同 input,resume 后断言其按到达/单调序重放。修复前该断言在 tie-break 落到随机 UUID 时不稳定
(顺序随 `transition_id` 变化);修复后稳定。

## 修复

给 `aipersimmon_process_transition`(或 parked input 取回所依赖的表)增加每实例单调列 `transition_seq BIGINT NOT NULL`,
沿用 effect 表 `seq` 的既有做法:

1. 各库内 transition DDL(h2 / postgresql / mysql,含 starter 与 jdbc 测试 schema,及 scaffold consumer 副本)加
   `transition_seq`。
2. `JdbcProcessTransitionStore` 增 `nextTransitionSeq(instanceId)`(`MAX(transition_seq)+1`,推进持有实例行锁故单调安全,
   同 `nextSeq`/`nextGeneration`),append 时写入。
3. `findParkedInputs()` 的 `ORDER BY` 改用 `transition_seq`;timeline / latest transition 查询一并对齐。

(注:多份 DDL 副本须同步变更——见 [[process-manager-schema-copies]]。)

## 关联

- [[issue-00016-per-instance-effect-ordering-not-guaranteed]] —— effect 侧同类"排序键非单调"问题及其 `seq` 修法(先例)。
- [[issue-00007-ordering-across-backoff-window]] —— outbox 侧同源顺序问题。
- [[process-manager-schema-copies]] —— DDL 多副本需同改。
- [[plan-00003-durable-process-manager-implementation]]
