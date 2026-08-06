---
id: issue-00169-nothing-warns-that-a-hand-written-write-must-advance-the-version
type: issue
status: resolved
---

# 手写 SQL 与版本化聚合共存时必须自己 `version = version + 1`，库里没有任何一处说过（P2，文档）

2026-08-04 写 S28 的 samples 时撞到（`aipersimmon-ddd-samples/s28-long-running-endpoints`）。
不是代码缺陷——`MybatisPlusAggregateRepository` 的行为完全正确——是**一条会静默损坏数据的前提条件
没有被写下来**，而库自己的中继在自己的表里恰好一直遵守它。

## 场景（不是杜撰的）

一个长耗时作业的**认领**不能是版本化写入：N 个 worker 本来就该同时抢，抢输不是信息。所以认领必须是
手写 SQL（`FOR UPDATE SKIP LOCKED` 候选 + 条件 `UPDATE`）——这正是库自己给 outbox 与 process-effect
中继写的形状（`aipersimmon-ddd-process-manager-mybatis-plus/.../lease/ProcessClaimMapper.java`）。

于是同一张表上出现了两个写入者：`saveAggregate`（带 `WHERE version = ?`）和认领语句（不带）。

## 现象（实测，S28 的负向对照之一）

认领语句里去掉 `version = version + 1`，其余一字不改：

| | 有 `version = version + 1` | 没有 |
| --- | --- | --- |
| 认领前读到聚合、认领后保存的取消 | `OptimisticLockingFailureException` | **静默提交** |
| 作业最终状态 | `RUNNING`（worker 继续跑，取消被拒后重试并改记为"请求停止"） | `CANCELLED`，而 worker 仍在把它跑完 |

77 个用例里只红 1 个，而且**红的方式是"没有抛异常"**——即
`FailureVisibilityTest.theclaimAdvancesTheVersionSoAStaleCancellationCannotWin`。
事后从数据看不出这是并发缺陷：它看起来就是一次成功的取消。

## 库里为什么找不到这条

`aipersimmon-ddd-persistence-mybatis-plus/src/main/java/com/aipersimmon/ddd/persistence/mybatisplus/MybatisPlusAggregateRepository.java:14-56`
的类 javadoc 把"容易做错的两件事"列了：写入带版本校验、事件随保存一起排空；并在
`:229` 附近详细解释了乐观锁拦截器的见证机制，在 `update(D)` 上详细解释了 `ClearedColumns` 陷阱。
**没有一句提到另一个写入者。** 同样地：

- `VersionedRow.java:11` 只说拦截器会把更新改写成 `SET version = version + 1 ... WHERE version = ?`；
- `AipersimmonDddPersistenceMybatisPlusAutoConfiguration.java:14` 同上；
- `aipersimmon-ddd/CONFIGURATION.md` 与 `README.md` 里 grep `version + 1` / `hand-written` 均无命中。

所以一个消费方按库自己的中继形状去写认领，是**照着库学的**，而库没告诉他这一步。

## 建议（三句话，不动代码）

在 `MybatisPlusAggregateRepository` 的类 javadoc 里加一段，大意：

> 这张表若还有别的写入者（认领、批量数据修复、运维脚本），那条语句必须自己
> `version = version + 1`。否则一个在它之前读到聚合的事务仍会提交——它校验的版本确实还是当前值——
> 而结果看起来不像并发缺陷，只像一次成功的写入。库自己的租约中继（outbox / process-effect）遵守这一条。

放在类 javadoc 而不是 `saveAggregate` 上：写手写语句的人不会去读 `saveAggregate` 的注释。

## 不建议的做法

不建议由库去检测（例如要求所有写入走某个门面）。认领必须绕开版本校验才有意义，
`saveAggregate` 也无从知道别人在做什么。这是一条**前提**，前提的正确位置是文档。

相关：[[analysis-00040-samples-long-running-endpoints]] §5。

## 解决记录（2026-08-05）

按建议只动文档，不动代码。`MybatisPlusAggregateRepository` 的类 javadoc 新增一节
"Two premises about the table, both of which bite on a schema this class did not design"，
第二段就是这一条：另有写入者时那条语句必须自己 `version = version + 1`，配一句 `UPDATE ... SET
state = 'RUNNING', version = version + 1 WHERE id = ? AND state = 'QUEUED'`，并写明后果的形状——
"不抛，事后也不像并发缺陷，只像一次成功的写入"，以及为什么这是前提而非库能检测的东西。放在类
javadoc 而不是 `saveAggregate` 上，理由同 issue。第一段是 issue-00171 的版本列默认值，两条前提
正好同源，合在一节里。

### 本条里有一处写错了，顺手改过来

原文说"库自己的租约中继（outbox / process-effect）遵守这一条"。**核实后不成立**：outbox 与
process-manager 的表根本没有乐观锁版本列——`aipersimmon_outbox` 的 `version INT NOT NULL`
（`V1__aipersimmon_outbox.sql:9`）是**事件类型版本**（`ce_version`），`OutboxRecord.version` 对应的
就是它；process-manager 的四张表 grep `version` 零命中。没有列，就无所谓"遵守"。

真正"两个写入者共存于一张带版本列的表"的地方只有 S28 自己，它的
`reconciliation/infrastructure/package-info.java` 也正是这么写的（"the only place in the samples
where those two coexist"）。所以新加的 javadoc **没有**引用库自己的中继作为示范——那句话会把读者
指向一个不存在的先例。
