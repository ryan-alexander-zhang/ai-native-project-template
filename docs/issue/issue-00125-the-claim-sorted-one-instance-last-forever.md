---
id: issue-00125-the-claim-sorted-one-instance-last-forever
type: issue
status: resolved
blocks: [issue-00119-ten-majors-were-never-scheduled]
---

# 认领把某个实例永远排在最后

`issue-00119` 排期第 8 档，对应 `report-00003` §2 的 #1 与 #2。

这一档排在最后是因为**没有量化证据就不好判断收益**。先测，再改——
结果两条里有一条的成因和报告写的不一样，另一条我原本打算加的索引**测出来完全没用**。

## #2：不是慢，是永远轮不到

`seq` 是**每实例**递增的（`nextSeq` = `MAX(seq) WHERE instance_id = ?` + 1）。
而 claim 的 `ORDER BY e.seq` 是**全局**的。

于是一个跑了很久的实例，它的 seq 恒大于任何新实例的 seq；配上 claim 上限，
它在**每一次**轮询里都排在所有新来者后面，被 LIMIT 截掉。真库复现（10 个新实例 + 1 个老实例，limit=10）：

```
claimed: [young-9-0, young-0-0, ... young-8-0]     ← 老实例一次都没出现
```

**这不是"变慢"，是这一个流程实例的永久停摆**，而队列整体看上去是健康的。
PG 和 MySQL 都是如此——这是 SQL 语义，与数据库无关。

改为按**工作到期的时间**排序（PENDING 看 `next_attempt_at`，租约过期看 `lease_until`）。
它是公平的，而且**会自我纠正**：等得越久越往前排。`effect_id` 做决定性平局打破，
理由与 `issue-00122` 的保留期扫描一样。

排序放宽到跨实例是安全的：`NOT EXISTS` 保证**每个实例最多只有一行是候选**，
所以一批里根本不可能同时出现同一实例的两个 effect 需要定序。

## #1：报告说是索引，其实是谓词——而且只在 PG 上

报告写的是「`NOT EXISTS` 过滤 `b.status`，而索引 `(instance_id, seq)` 不含 status」。
按这个说法，修法应该是把 status 加进索引。**测下来这个修法一点用都没有。**

PostgreSQL 18，一个实例带 20 万条已投递 effect：

| 方案 | buffers | 执行时间 |
|---|---|---|
| 现状（`<>` + 全局 seq 排序） | **46,813** | **86.8 ms** |
| 加复合索引 `(instance_id, status, seq)` | 436 → **计划完全没变，索引没被用** | 1.45 ms |
| 部分索引 `(instance_id, seq) WHERE status <> 'DELIVERED'` | 14 | 0.056 ms |
| **只把谓词写成两个区间，不加任何索引** | **66** | **0.088 ms** |

真正的成因是 **`<>` 在 PG 上不可 seek**，与索引里有没有 status 无关。
`b.status <> 'DELIVERED'` 对 NOT NULL 列等价于 `b.status < 'DELIVERED' OR b.status > 'DELIVERED'`，
后者给出两个可 seek 的区间，把 DELIVERED 那一整块直接跳过。

**而 MySQL 的优化器自己就在做这个改写**——它的基线计划里白纸黑字：

```
-> Filter: (b.`status` <> 'DELIVERED')
    -> Index range scan on b using idx_process_effect_due
       over (status < 'DELIVERED') OR ('DELIVERED' < status)
```

所以 #1 **只是 PostgreSQL 的问题**，报告没有区分。写成两个区间，
等于让 PG 也走 MySQL 本来就在走的那条计划。

**代价从"随实例历史增长"变成"随未结清工作量增长"**——后者是运维本来就在盯的量：
历史从 2 万涨到 20 万，新查询执行时间纹丝不动（0.089 → 0.088 ms）；
把历史固定、未结清工作从 11 涨到 2001，才涨到 0.909 ms。

### 为什么不枚举状态

`status IN ('PENDING','IN_FLIGHT','DEAD','CANCELLED')` 效果一样（16 buffers）。
但它把 SQL 和 `EffectStatus` 绑死了：**将来加一个状态而忘了改 SQL，
新状态会悄悄地不再阻塞后面的 effect**——一个正确性隐患，比性能隐患坏。
两区间写法对任何新状态自动成立。

### 不加迁移

部分索引只快 2 个 buffer，且 MySQL 没有部分索引、H2 也没有——
为此引入一个三方言各不相同的索引不值得。**这一档最后一行迁移都没写。**

## 顺带纠正我自己写错的注释

`DEADLINE_CANDIDATE_SQL` 以 `ORDER BY d.due_at` 结尾，**没有平局打破**。
而我在 `issue-00122` 和 `JdbcProcessRetentionStore` 的注释里都写了
"与 deadline claim 的 `(due_at, deadline_id)` 同一个约定"。

这个约定是真的，但它在 `JdbcProcessDeadlineStore` 的**列表查询**上，
**claim 从来没跟上**。deadline 由业务时长设定，一批同时到期是常态而非边界，
所以这里的平局加上批量上限同样能让一部分永远不被点燃。补上 `d.deadline_id`。

## 两份拷贝合成一份

同一段 claim SQL 在 jdbc 与 mybatis-plus 各有一份手工维护的拷贝。
本档之后**两处的拼写都是承重的**——谁把两区间"整理"回 `<>`、
把 due 排序"整理"回 `seq`，代码照样返回正确的行，只是慢或不公平，不会有任何东西反对。

我先加了一个跨模块的比对测试，**结果它自己炸了**：给 mybatis-plus 加上 jdbc 的
test 依赖，会把 jdbc 的自动配置也带上测试 classpath，两个后端在 `processSchemaValidator` 上撞车——
这恰好说明两个后端本来就该互斥。

于是改成**消灭重复**而不是巡查重复：SQL 移到 engine 模块（三方言的迁移脚本本来就在那儿，
关系型 schema 一直是 engine 的契约）。用 MyBatis 的 `#{now}` 占位符书写，
jdbc 侧在类初始化时改写成 `?`。**一份拷贝不会和自己漂开**，比对测试也就随之删掉了。

## 负向对照

排序与 deadline 平局各做一次 revert，均**断言 revert 真的落地**后变红：

- 还原全局 `seq` 排序 → 3 条红，老实例的那条给出 `[e-fresh-9 ... e-fresh-5]`，veteran 不在其中
- 还原 deadline 无平局打破 → 1 条红，返回 `[d-3, d-2]`（插入顺序，不是 id 顺序，所以不是空测试）

代码结构中途变了（SQL 移进 engine），**两条对照都对着最终落盘的代码重跑了一遍**。

第三处改动（两区间）**对照是绿的，这正是预期**：它只改执行计划，不改语义——
真要变红，说明我的改写动了语义，那才是 bug。
它的依据是量化测量，不是测试；测试只钉住"每一个非 DELIVERED 状态仍然阻塞"（两个区间各覆盖两个状态）。

## 关联

- 父：[issue-00119-ten-majors-were-never-scheduled](issue-00119-ten-majors-were-never-scheduled.md)（排期第 8 档）
- 平局会饿死，同一形状的先例：[issue-00122-the-four-process-tables-grew-forever](issue-00122-the-four-process-tables-grew-forever.md)
- 被本档纠正的注释也在 `issue-00122` 里
- 对照必须断言 revert 落地：[issue-00124-the-rules-pointed-at-a-door-the-wiring-had-nailed-shut](issue-00124-the-rules-pointed-at-a-door-the-wiring-had-nailed-shut.md)、[issue-00123-the-rate-limiter-deleted-the-window-someone-was-counting-in](issue-00123-the-rate-limiter-deleted-the-window-someone-was-counting-in.md)
