---
id: issue-00171-the-write-path-against-a-schema-the-library-did-not-design
type: issue
status: resolved
---

# 聚合写入路径撞上"不是自己设计的 schema"：两处，都不是缺陷、都是错误信息指向错的原因（P2，文档/信息）

2026-08-05 写 S25 的 samples 时撞到（`aipersimmon-ddd-samples/s25-strangler-legacy-adoption`）。
两处**行为都正确**，问题都在**异常信息把读者指向了错的原因**——而这两处恰好是遗留表必然会踩的两处，
也就是最需要信息准确的两处。

## 一、`version` 列默认 0，于是每一行历史数据都"看起来没保存过"

`MybatisPlusAggregateRepository.saveAggregate` 用 `version == 0` 表示"尚未持久化"，走 INSERT 分支
（`MybatisPlusAggregateRepository.java:88-95`，javadoc 里也明确写了这条语义）。

遗留表加版本列时最自然的写法是：

```sql
ALTER TABLE legacy_refunds ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
```

于是**几百万行历史数据全部拿到"未保存"这个值**，对任何一条迁移前的旧行的第一次写入都是一次
INSERT 一个已经存在的行。实测（S25 负向对照 1，把 `DEFAULT 1` 改回 `DEFAULT 0`）：**2 红 / 48**，
其中关键那条抛的是

```
DuplicateEntityException: aggregate Refund[10] already exists. Either two concurrent creates raced on
the same identity — a genuine conflict the client should see as 409 — or this aggregate was
reconstituted by a factory that forgot to call restoreVersion(...), leaving its version at 0 so save
took the insert branch; if this write was meant to be an update, that is the bug to fix.
```

信息本身写得很细，**列的两个原因在这里都不成立**：没有并发创建，工厂也没忘记 `restoreVersion`——
它老老实实还原了列里的那个 0。真正的原因是列默认值。

**建议**：在这条信息里加第三个可能，一句话即可，大意"或者这个聚合来自一张后加版本列的既有表，
而那一列的默认值是 0——0 在本库里表示未持久化，请用 1"。以及在
`MybatisPlusAggregateRepository` 的类 javadoc 里，"version == 0 表示未持久化"那句后面补一句
"因此为既有表增加版本列时默认值必须是 1"。

## 二、自增主键：INSERT 会**通过**，第二次写才失败

原以为 `saveAggregate` 会拒绝没有主键的行（`idValueOf` 的信息就是为此写的）。**实测不是**：
那个守卫只在 **update** 路径上（`MybatisPlusAggregateRepository.java:180-190`，被 `update(D)` 调用），
insert 路径没有对应检查，于是 `IdType.AUTO` 的行被数据库正常分配了 id。

结果比"被拒绝"更糟，而这是本条的要点：

| | 实测 |
| --- | --- |
| 第一次写 | **成功**。行进了库，id 由数据库分配 |
| 内存里的聚合 | id 还是构造时那个值，**与行里的不一致** |
| 事件 | 已按那个错的 id 发布出去了 |
| `versionAdvanced()` | 已经调用 |
| 第二次写 | 才抛 `IllegalStateException: came back from toRow with no primary key value` |

也就是说错误归属**已经落库、并且已经流到下游**，才有人报错。而那条信息说的是
"an update would match every row of the table"——对 update 路径是准确的，对读者此刻的处境
（我插入了一行，id 丢了）指错了方向。

**建议**：在 insert 路径上加一个同样的前置检查，信息里点明自增列的情形——大意
"toRow 没有给出主键。如果这张表的主键由数据库分配（BIGSERIAL / AUTO_INCREMENT），请在插入前
自行取号（例如 `nextval(pg_get_serial_sequence(...))`）：本库的写入路径需要在插入之前就知道身份，
因为聚合的事件与版本都以它为准"。三行代码，把一个"事后才发现"变成"当场失败"。

## 不建议的做法

不建议让库去支持"数据库分配主键"。`saveAggregate` 在插入后发布事件并推进版本，两者都需要身份；
要支持就得在插入后回读并回填聚合的 id，而聚合的 id 是 final 的、也应该是 final 的。
**取号前置是正确的形态**，缺的只是让人当场知道这件事。

## sample 侧的现状

`RefundIds.reserve()`（`nextval(pg_get_serial_sequence('legacy_refunds', 'id'))`）把号前置；
V2 迁移用 `DEFAULT 1` 并在注释里写明为什么不是 0。两处都有实测的测试钉着
（`AutoIncrementIdentityTest.anautoIncrementInsertSucceedsAndTheRowGetsAnIdTheApplicationNeverLearns`、
`thesecondWriteIsWhereTheMissingKeyIsCaught`、`aversionOfZeroMakesAPreExistingRowLookLikeANewAggregate`）。

相关：[analysis-00042-samples-strangler-legacy-adoption](../analysis/analysis-00042-samples-strangler-legacy-adoption.md) §3。

## 解决记录（2026-08-05）

两处都按建议改了，第二处动了代码（把"事后才发现"变成"当场失败"）。

### 一、版本列默认值

`DuplicateEntityException` 的信息加了第三个可能：*"A third cause looks like neither: the row comes
from a table whose version column was added with DEFAULT 0 … Retrofit such a column with DEFAULT 1."*
`insertExactlyOnce` 的 javadoc 从"names both plausible causes"改成"names the plausible causes"并列出三条。
类 javadoc 新增一节的第一段讲这条，带一句 `ALTER TABLE legacy_refunds ADD COLUMN version BIGINT NOT
NULL DEFAULT 1;  -- not 0`。（该节第二段是 issue-00169，两条前提同源。）

### 二、自增主键：insert 路径的前置检查

新增 `requirePrimaryKeyBeforeInsert(row, aggregate)`，在 `mapper.insert` 之前跑。信息按建议点名自增列
的情形并给出取号办法（`nextval(pg_get_serial_sequence(...))`），并说明为什么必须前置：
"the aggregate's events and its version are both recorded against it, and an id read back afterwards
would arrive too late for either"。`TableInfo` 或 `keyProperty` 缺失时的信息与 update 路径一致
（提示补 `@TableId`）。update 路径那条守卫与它的信息**原样不动**——两者失败的原因不同，
一个是"会匹配全表"，一个是"会静默成功"，所以是两条信息。

**存量安全性查过**：全仓（库 + scaffold + 25 个 sample）走 `MybatisPlusAggregateRepository` 的行
全部是 `IdType.INPUT`；唯二的 `IdType.AUTO` 是 outbox 自己的 `OutboxRecord` / `DeadLetterRecord`
（不走这个基类）和 S25 这个刻意的测试 fixture。

**测试**（`MybatisPlusAggregateRepositoryTest` 从 9 条到 12 条）：

- `aDuplicateKeyOnInsertAlsoNamesTheRetrofittedVersionColumn`：断言信息里有
  `version column was added with DEFAULT 0` 与 `DEFAULT 1`；
- `aninsertWithNoPrimaryKeyIsRefusedBeforeAnythingIsWritten`：新增 `IdlessThings`（`toRow` 不 setId），
  断言抛 + `verify(mapper, never()).insert(...)` + 未发布事件 + 未写子表 + 版本未推进——
  四个后果都断言，因为原缺陷的要害正是这四件事都已经发生了；
- `anupdateWithNoPrimaryKeyStillReportsMatchingEveryRow`：update 路径仍是它自己那条信息。

**负向对照**：删掉 `requirePrimaryKeyBeforeInsert(row, aggregate);` 这一行，恰好红 1 条
（`aninsertWithNoPrimaryKeyIsRefusedBeforeAnythingIsWritten`），且是"抛了但信息不对"——因为
mock 的 `insert` 默认返回 0，落到既有的 "zero rows" 分支。**这也是这条单测的边界**：它证明的是
新信息与"insert 未被调用"，证明不了"真库里这个 insert 会成功"——那一半是 S25 对真 Postgres 的
集成测试证的。update 那条照旧绿（那条守卫本来就在）。恢复后 12 全绿。

**验收**：S25 两条用例按预期改向——
`anautoIncrementInsertSucceedsAndTheRowGetsAnIdTheApplicationNeverLearns` 改名为
`anautoIncrementInsertIsRefusedBeforeTheRowCanGetAnIdTheApplicationNeverLearns`，断言抛
`IllegalStateException` + 信息含 `BIGSERIAL` + **表里一行都没有**；
`thesecondWriteIsWhereTheMissingKeyIsCaught` 改名为
`theupdatePathHasItsOwnMissingKeyMessageAboutMatchingEveryRow` 并直接走 update 路径（不再靠先插一行
才够到它），因为那两条守卫存在的理由不同，这样陈述才准确。
`aversionOfZeroMakesAPreExistingRowLookLikeANewAggregate` 加断言钉住新增的第三个原因。
库 full 绿，25 个 sample 全绿，scaffold 绿。
