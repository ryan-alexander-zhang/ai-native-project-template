---
id: issue-00115-clearing-a-field-never-reached-the-database
type: issue
status: resolved
blocks: [report-00003-ddd-library-review-2026-07-29]
---

# 领域方法清空的字段，从来没有写进数据库

## 症状：一条被接受的命令，静默地只执行了一半

`MybatisPlusAggregateRepository.saveAggregate` 用 `mapper.updateById(row)`。
MyBatis-Plus 默认 `NOT_NULL` 策略会把 null 字段**从 `SET` 子句里剔除**。

这对**部分更新**是对的——null 意思是"我没打算说这一列"。
但**保存聚合从来不是部分更新**：`toRow` 映射的是整个根，null 在这里的意思是"这个字段现在空了"。

而每一个环节都报告成功：

| 环节 | 表现 |
|---|---|
| 乐观锁检查 | **通过**——version 确实推进了 |
| `requireVersionWasChecked` | **通过**——拦截器确实回写了版本 |
| 领域事件 | **照常发布**——下游被告知变更发生了 |
| 数据库 | **旧值原封不动** |
| 下次加载 | 僵尸字段复活，撤销了命令的一半 |

全程无异常、无日志、无任何信号。这是本次评审两大系统性主题里「静默降级」的**最后一个实例**，
而且它在**写路径**——报告称为"全框架最扎实的部分"、其余一切都建在其上的地方。

**框架在别处早就认出过这个陷阱**：`MybatisOutboxStore.clearLease` 用的正是 wrapper 的 `.set(field, null)`，
注释写着"MP 默认字段策略会把 null 字段从实体更新里丢掉——而清租约恰恰需要写进去"。
同一个陷阱，outbox store 里认出来了，**聚合仓储基类里没有**——而后者才是消费方每个聚合都要过的那条路。

## 修法：走 `update(entity, wrapper)`，两半保证都不动

```
wrapper.eq(idColumn, id)            // update 没有隐含的 id 谓词，自己给
ClearedColumns.forceOnto(wrapper, row)   // 被清空的列显式 set 回去
mapper.update(row, wrapper)         // entity 仍然传，供其余列 + 拦截器
```

**实体仍然传进去，这一点是关键**：已回读 `OptimisticLockerInnerInterceptor` 源码确认，
它对 `update(et, wrapper)` 与 `updateById(et)` **一视同仁**——都以 entity 参数为钩子，
把 version 谓词 `apply` 到我这个 wrapper 上，并把自增后的版本**回写到实体**。
所以 `requireVersionWasChecked`（`issue-00107` 立的那道断言）一个字都不用改，
乐观锁的两半（谓词 + 见证）原样成立。

MP 的 `update` SQL 是 `UPDATE t <set> et 的非空列 + ${ew.sqlSet} </set> WHERE ew`——
实体负责非空列，wrapper 负责被清空的列，**不重复**。

## 该强制写哪些列：读 MP 自己的元数据，不靠猜

两个边界情况**咬的方向相反**，这就是不能只处理默认策略的原因：

- 应用配了 **`ALWAYS`** 的列，实体的 `SET` **已经**带上了。再加一遍会生成
  `SET c = ?, c = null`——**MySQL 接受、PostgreSQL 直接拒绝**。猜错的库会在一套测试环境里绿、
  在另一套里炸。
- 应用配了 **`NEVER`** 的列，是它明说"这列不归你写"。因为行对象那里恰好是 null 就把它强制清空，
  等于**按框架的臆断销毁数据**。

所以 `ClearedColumns` 复刻的是 MP 自己 `TableFieldInfo.convertIf` 的判定
（`NEVER` → 从不发出；`isPrimitive || ALWAYS` → 总是发出；`NOT_EMPTY` 且是 `CharSequence` → null 或空串才算空；
其余 → null 才算空），并额外排除三类，各自的理由写在排除它的地方：
**填充列**（`withUpdateFill`，MP 不加 `<if>` 包裹）、**version 列**（归拦截器管）、
**逻辑删除列**（MP 在普通更新里刻意排除它——保存聚合不是删除行）。

`updateStrategy` 取的是 `TableFieldInfo` 上**已解析**的值（`DEFAULT` 在建表信息时就按全局配置解析掉了），
所以一个把全局默认改成 `ALWAYS` 的应用同样正确。

## 验证：必须打真库

**这个缺陷在生成的 SQL 里，mock 掉的 mapper 看不见它**——本模块原有的那个测试正是 mock mapper，
**全程一直是绿的**。所以新增 `ClearingAFieldReachesTheDatabaseTest`（H2 + 真 MyBatis-Plus 会话
+ 真的乐观锁拦截器），5 例：

1. 清空字段 → 列真的变 null；
2. 版本仍然推进，且**用过的版本再写仍被拒**（重写 SET 子句没有代价掉那条谓词）；
3. 配了 `ALWAYS` 的列写一次而不是两次；
4. 配了 `NEVER` 的列原封不动；
5. 没被清空的字段照常写入。

**负向对照**：把 `update(...)` 换回 `updateById(...)`，第 1 例按预期失败，列里还是旧值。

脚手架 `multi-module` 对**真实 PostgreSQL/MySQL**（Testcontainers）跑完 78 组测试全绿——
包括 `ConcurrentApprovalTest` 那条真 HTTP + 真库并发审批，也就是新 SQL 在真数据库上的端到端证明。

## 一处连带

原有的 mock 测试里那个 `ThingRow` 没有 `@TableId`，因为 mock 掉 mapper 意味着 MyBatis 从没解析过它。
基类现在要读 MP 的表元数据来给 update 定位主键，所以那个测试改为**用
`TableInfoHelper.initTableInfo(...)` 自己注册一次**——而不是把生产路径放宽到接受"未注册的实体"，
那种实体在真实应用里根本不存在（`BaseMapper<D>` 能注入进来就意味着 MyBatis 解析过 `D`）。

## 关联

- 父：[report-00003-ddd-library-review-2026-07-29](../report/report-00003-ddd-library-review-2026-07-29.md)（§2 持久化「`MybatisPlusAggregateRepository:82` 用 `updateById`」那条，报告标注"**仍然开着**"）
- 乐观锁的另一半见证断言，本项完全复用：[issue-00107-silent-degradations-become-loud-failures](issue-00107-silent-degradations-become-loud-failures.md)
- 框架在 outbox store 里早已认出同一个陷阱：[issue-00108-a-killed-relay-instance-stops-all-delivery](issue-00108-a-killed-relay-instance-stops-all-delivery.md)
- 乐观锁协议本身：[design-00011-aggregate-persistence-contract](../design/design-00011-aggregate-persistence-contract.md)
