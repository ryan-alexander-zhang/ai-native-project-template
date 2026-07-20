---
id: issue-00016-per-instance-effect-ordering-not-guaranteed
type: issue
role: main
status: resolved
parent: plan-00003-durable-process-manager-implementation
---

# 每实例效果顺序无强保证:`created_at` 并列即失序(排序键非单调)

## 问题(现状,file:line 为证)

- **等级:Medium(触发时影响 High)**。
- "每实例串行、按序投递"只由候选查询的 head-of-line 谓词保证,排序键是 `(created_at, effect_index)`:
  `lease/JdbcProcessDialect.java:48-58`(`CANDIDATE_SQL`)。
- `effect_index` **每个 transition 从 0 重新计**(`runtime/JdbcProcessRuntime.java:307` 起 `index=0`),且表内**无每实例单调序列列**
  (`postgresql-schema.sql` 仅 `UNIQUE(transition_id, effect_index)`,`created_at` 为普通 `TIMESTAMP`)。
- 于是同一实例、**不同 transition** 的两个 `effect_index=0` 的效果,若 `created_at` 相等,谓词
  `b.created_at < e.created_at OR (b.created_at = e.created_at AND b.effect_index < e.effect_index)` 对它们互不成立
  → 互不阻塞 → 同批可并发认领 / 乱序投递(例如补偿先于其创建命令)。
- 触发条件现实存在:粗粒度时钟、`created_at` 列精度截断、**NTP 回拨**;测试里的 `Clock.fixed` 让**所有** `created_at` 相等。

## 根因(第一性)

1. **观察 vs 期望**:期望"对一个实例,效果按因果顺序、前一条落定(DELIVERED)前不投后一条";实际"两条不同 transition、
   同 `created_at`、同 `effect_index` 的效果互不阻塞"。
2. **最小机制**:顺序不变式被编码在 `(created_at, effect_index)` 上,但 `created_at` 不保证每实例严格单调,`effect_index`
   跨 transition 归零、`transition_id` 为随机 id——**没有任何一个每实例单调的排序键**。
3. **真根因**:缺一个每实例单调序列。与 [[issue-00007-ordering-across-backoff-window]] 同源——顺序约束必须落在一个
   可靠的单调键上,不能依赖墙钟。deadline 侧已有 `generation`(MAX+1、在实例行锁内)先例可循。

## 复现(test-first)

`JdbcProcessEffectRelayTest#twoUndeliveredEffectsOfOneInstanceAreDeliveredOneAtATime`:同实例连做两次推进(start + advance),
两条效果都 `PENDING`、`Clock.fixed` 下 `created_at` 相等、各自 `effect_index=0`;断言一次 `pollOnce()` 只投 1 条(head),
第二条须等第一条 DELIVERED。修复前一次投 2 条(串行保证被击穿)。

## 修复

给 `aipersimmon_process_effect` 增加每实例单调列 `seq BIGINT NOT NULL`:

1. 六份库内 DDL(starter 的 h2/postgresql/mysql + starter 测试 `schema.sql` + jdbc 测试 h2/postgres)加 `seq`,并把
   `idx_process_effect_instance` 由 `(instance_id, effect_index)` 改为 `(instance_id, seq)`;consumer 侧的 scaffold
   multi-module `start/schema.sql` 副本同步(其端到端履约验收测试佐证)。
2. `JdbcProcessEffectStore` 增 `nextSeq(instanceId)`(`MAX(seq)+1`,推进持有实例行锁故单调安全,同 `nextGeneration`),
   `insert` 写入 `seq`;`ProcessEffectInsert` 增 `seq`。
3. `JdbcProcessRuntime.stageEffects` 每次推进取一次 `seqBase = nextSeq(instance)`,第 `i` 条效果 `seq = seqBase + i`
   (跳过的 deadline/cancel 索引留空隙无害,只需单调)。
4. `JdbcProcessDialect.CANDIDATE_SQL` 的阻塞谓词与 `ORDER BY` 改用 `seq`(`b.seq < e.seq` / `ORDER BY e.seq`);
   `redrive` 保留原 `seq`,顺序不变。

## 验证结果

- 新回归测试通过;`deliversEffectsOfOneInstanceSeriallyInOrder`、PostgreSQL/MySQL 并发 gate 不回归。
- jdbc + starter 模块 test 全绿。

## 关联

- [[issue-00007-ordering-across-backoff-window]] —— outbox 侧同类顺序问题的先例与修法。
- [[plan-00003-durable-process-manager-implementation]]
