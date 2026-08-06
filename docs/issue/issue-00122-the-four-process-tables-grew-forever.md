---
id: issue-00122-the-four-process-tables-grew-forever
type: issue
status: resolved
blocks: [issue-00119-ten-majors-were-never-scheduled]
---

# 流程管理器的四张表，永远只增不减

`issue-00119` 排期第 3 档。

## 症状

全树 `DELETE FROM aipersimmon_process_*` **零条**。实例快照、转移日志、效果、定时器
四张表从部署第一天起只增不减。

**而 outbox 的 cleanup 是第 6 项抽 engine 时顺手带的**——流程管理器的同一个问题一次都没被提起。
这正是 `issue-00119` 说的"是注意力，不是优先级"。

## 删除单位是整个实例，不是行

四张表是**一条记录**。删掉转移而留下实例，会留下一个时间线在说谎、
且 `findLatestTransitionId` 什么都找不到的实例——而那个状态 **runtime 直接拒绝回答**
（`issue-00117` 刚为它写过测试："instance without any transition"）。

所以：要么整个实例连同它的一切一起走，要么一个都不动。

## 「已结束」不等于「可以删」

两种状态看起来结束了，其实没有：

| 状态 | 为什么留 |
|---|---|
| effect 是 `PENDING` / `IN_FLIGHT` | **终态决策取消的是定时器，暂存的效果照常投递**——一段流程的最后一个事件恰恰就是这种效果。此时删实例会把效果一起删掉，什么都不发，也不留痕 |
| effect / deadline 是 `DEAD` | 那是**一个从未落地的副作用的记录**，操作员还能 redrive。删掉它等于销毁"曾经欠着什么"的证据——与保留策略的目的正好相反。**与 outbox 的 purge 不碰死信表同一个理由** |

于是判据是：已结束 + 保留期已过 + 它持有的每一个 effect 与 deadline 都已结清
（delivered / fired / cancelled）。

## 默认关闭

同 outbox purge，同一个理由：**删除业务记录、以及保留多久，是部署方的决定，不是库的默认值。**
开关没打时表继续增长——这正是本项要终结的状态，但仍然好过一个库擅自删掉某人指望还能找到的记录。

## 不用 ShedLock

这与本模块**其余所有协调方式**一致：relay 与 deadline worker 靠 claim 里的租约，
parked-input worker 靠重放的幂等性。两个实例同时 purge 会选到重叠的 id 并都发出删除，
第二个删掉零行——**每小时一点重复劳动，且不可能损坏任何东西**，
比引入一把"过期时间又成了一件要推理的事"的锁便宜。

## 一个测试找出来的真问题：平局会饿死

我先写的排序是 `ORDER BY updated_at`，测试断言了一个 SQL 并未承诺的顺序，于是失败了。

**这不是测试写错了，是 SQL 少了一半**：平局在这里是**常态**（一批实例同时完成会共享同一秒），
而配上批量上限，不稳定的顺序可能每次都取到同一个子集，
**让平局后面的某个实例永远轮不到**——正是报告给 effect claim 提的那个饿死问题的同一形状。

补上 `instance_id` 作为决定性平局打破，与 deadline **列表查询**的 `(due_at, deadline_id)` 同一个约定。

> 更正（[[issue-00125-the-claim-sorted-one-instance-last-forever]]）：此处原文写的是「与 deadline **claim** 同一个约定」，
> 而 deadline claim 当时**并没有**平局打破——这个约定只存在于 `JdbcProcessDeadlineStore` 的列表查询上。
> claim 的那一半由 `issue-00125` 补上。

## V5 索引，三方言

保留期扫描按 `(lifecycle, updated_at)` 走。没有索引它就是**对每一个跑过的实例做全表扫描**——
而那正是这个 purge 要阻止其增长的表，**扫描会随着对它的需求同步变慢**。

PG/H2 用 `CREATE INDEX IF NOT EXISTS`，MySQL 用 `ALTER TABLE ... ADD INDEX`（它两个 IF NOT EXISTS 都不支持，
由 Flyway 的版本账本保证只跑一次）——沿用 V4 已经定下的每方言写法。

## 测试放在它运行的地方

判据是 SQL，所以**对着数据库测**（H2，跑真实迁移脚本 V1–V5），
且 fixture 一律经**真 store** 建行再微调，这样它不会和生产代码写的 schema 漂开
（第一版手写 INSERT 直接撞上 `business_step` / `state_payload_type` 这些我猜错的列名）。

真正要紧的是那些断言**保留**的用例：purge 太急会销毁业务记录，太怯只是什么都不做。

引擎侧另测它自己决定的事：批量上限、选与删在同一个事务里
（否则两者之间 relay 可能给一个已被判定为完成的实例暂存效果，而删除会连它一起带走）。

## 关联

- 父：[[issue-00119-ten-majors-were-never-scheduled]]（排期第 3 档）
- "删掉转移会让 runtime 拒绝回答"，那条断言来自：[[issue-00117-the-advance-itself-had-no-tests]]
- 终态决策仍然投递暂存效果，这条设计来自：[[issue-00104-an-ended-instance-keeps-its-timers-forever]]
- 抄的形状与"不碰死信表"的先例：[[decision-00020-outbox-engine-over-one-store-port]]
