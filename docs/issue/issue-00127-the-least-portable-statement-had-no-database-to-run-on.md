---
id: issue-00127-the-least-portable-statement-had-no-database-to-run-on
type: issue
role: main
status: resolved
parent: issue-00119-ten-majors-were-never-scheduled
---

# 最不可移植的那条语句，没有一个数据库跑过它

`issue-00119` 排期第 7 档（最后一档），对应 `report-00003` §2 的 #4。

## 缺口

deadline claim 的所有测试都跑 H2，而 **H2 走 `AtomicUpdateProcessDialect`——完全是另一条语句**。
于是 `SkipLockedProcessDialect.claimDueDeadlines` 里这句：

```sql
... FOR UPDATE OF d SKIP LOCKED
```

**第一次遇到真的 PostgreSQL 或 MySQL，是在生产里。** 它锁的是 join 的其中一侧，
是本模块里最不可移植的一处写法。

这正是 MariaDB 那条缺陷的形状（`issue-00120`）：**一条 claim 如果解析不了，不是变慢，
是每一轮轮询都失败、定时器永远不触发、而且不响。**

## 结论：语句是对的，只是从来没人验证过

PG 18 与 MySQL 8 上 8 条断言全绿。**顺带也是我自己第 8 档那个 `d.deadline_id` 平局打破
第一次在这两个库上跑**——它此前同样只在 H2 上验证过。

## 但"对着能用的代码通过"什么都不证明

所以做了对照：把 `FOR UPDATE OF d SKIP LOCKED` 去掉。

**两个库都把每一个 deadline 认领了两遍**（期望 40，实得 80）——正是这条 claim 要防的重复触发。
测试确实抓得住它要抓的东西。

## 一个被新测试撞出来的既有顺序依赖

加完新类，`DeadlineCancelMysqlTest` 开始失败：

```
Can't DROP 'trace_id'; check that column/key exists
```

它的 setUp **只 drop deadline 一张表**，然后跑 V1–V4。而 **V1 是 `CREATE TABLE IF NOT EXISTS`
四张全建、V2 又从其中三张 drop 掉一列**——于是：前面若有别的类跑过，剩下的三张表已经在 V2 之后的状态，
V1 对它们什么都不做，V2 再去 drop 那个已经不存在的列就炸。

**它一直依赖自己是第一个跑的**，我只是换了执行顺序把它照了出来。
改成和邻居们一样 drop 全部四张——真正的缺陷是"setUp 依赖别的测试类留下的状态"，
不是名字排序。

## 关联

- 父：[[issue-00119-ten-majors-were-never-scheduled]]（排期第 7 档，最后一档）
- 同一形状的真缺陷（解析失败 = 每轮静默失败）：[[issue-00120-mariadb-was-support-nobody-had-declared]]
- 本档顺带验证了它的改动：[[issue-00125-the-claim-sorted-one-instance-last-forever]]
