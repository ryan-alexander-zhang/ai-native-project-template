---
id: analysis-00033-samples-strong-consistency-seata
type: analysis
status: draft
parent: analysis-00014-ddd-samples-scenario-catalog
---

# S10 强一致性：Seata 跨服务分布式事务

对应 sample：`aipersimmon-ddd-samples/s10-strong-consistency-seata`（两个服务 + 一个端到端测试模块，
两个数据库，一个 seata-server）。场景清单见 [analysis-00014-ddd-samples-scenario-catalog](analysis-00014-ddd-samples-scenario-catalog.md)。

## 0. 本篇定位

业务上不接受任何中间态窗口：扣款与加分必须同成同败，且调用方同步拿到结果。库本身不含 Seata 集成，
本篇要回答的是**第三方分布式事务框架能不能和本库的写路径共存，以及代价是什么**。

结论先放：**能，而且库一行都不用改**。真正的代价不在代码里，在部署和运维里。

## 1. §3.1 的阻塞前提：AT 与两层 SQL 改写共存（已验证通过）

清单把这条列为动工前必须跑通的验证：Seata AT 靠解析它拦到的 SQL 生成前后镜像，而本库的聚合写在到达
Seata 之前已经被改写过两次——乐观锁加 `SET version = version + 1 ... WHERE version = ?`，租户行加
`AND tenant_id = ?`。

**验证方式**：先写了一个独立探针（一个带 version 列、带 tenant 列、复合主键的聚合，在 AT 模式下被全局
回滚），再把结论落成 sample 里的常驻测试。直接读 `undo_log.rollback_info` 的内容，而不是从"测试绿了"
反推：

| 观测到的内容 | 说明什么 |
| --- | --- |
| 前后镜像都有 `"name":"version","keyType":"NULL"`，前 1 后 2 | version 被当普通列捕获，回滚会把它还原 |
| `tenant_id` 与 `id` 都是 `"keyType":"PRIMARY_KEY"` | Seata 从表元数据取的复合主键，不是从被改写的谓词里猜的 |
| 后镜像里 `"name":"last_note",...,"value":null` | 框架的 cleared-column 强制的 `SET ... = NULL` 被解析进了镜像，没被丢掉 |
| 锁键 `s10_points_account:acme_shared-loyalty;s10_points_entry:acme_contend-at` | 全局锁按完整复合主键取；且**聚合的子行和根行一起被锁** |

**为什么能共存，一句话**：拦截器在 DataSource **之上**改写 SQL，而 Seata 的代理**就是** DataSource——
等 Seata 开始解析时改写早已完成，它看到的是普通的最终 SQL。所以 AT 成为本篇主线，TCC 作为可度量的
对照而不是退路。

version 还原这一条值得单独强调：如果回滚只还原了余额而把 version 留在 2，那么行的数据是对的、
而任何持有 version=1 快照的写入者会永久失败，乐观锁会报一个已经不存在的冲突。

## 2. undo_log 不是应用能迁移的表（本篇最先撞到的墙）

启动即失败：`IllegalStateException: in AT mode, undo_log table not exist`。

原因是 Seata 在 `DataSourceProxy.init` 里**无条件**检查这张表，而那发生在 **DataSource bean 构造期间**;
Flyway、`spring.sql.init`、本库的 flyway components 全都在 DataSource 之后运行，因为它们都依赖
DataSource。没有任何属性能推迟或关掉这个检查。

所以 undo_log 属于数据库侧：sample 用 compose 的 init hook 建，测试用 `withInitScript` 建。两个连带
影响都提前写进配置而不是事后踩：

- schema 不再是空的，Flyway 需要 `baseline-on-migrate: true`;
- 且 `baseline-version` 必须是 `0`，否则 Flyway 在 1 上打基线并把 V1 当成已应用——报错时间点很晚，
  且表现为"表不存在"而不是迁移错误。

**这不是库的问题**，是 Seata 的约束；但它确实限制了本库 flyway 组件机制的适用范围，值得在选型时知道。

## 3. 全局事务边界画在哪一层

```
HTTP  →  @GlobalTransactional（application service）  →  @Transactional（command bus）  →  聚合
```

在命令总线**上面一层**，而这个顺序不是风格问题：一个分支**就是**一次已提交的本地事务加它的 undo log，
所以本地事务必须在全局事务内部开始并结束。放在下面就没有分支可以回滚；放在 controller 上，业务事务的
边界就由 URL 决定了——下一个入口（定时任务、消息消费者、运维工具）会静默地完全没有事务。一条 ArchUnit
规则钉住这条线。

两个相邻注解的默认值相反，用之前值得知道：Spring 的 `@Transactional` 只对非受检异常回滚；Seata 的
`@GlobalTransactional` 对任何 `Throwable` 回滚。

## 4. "强一致性"到底保证了什么

**不是"不存在中间态"。** 扣款的本地事务真的提交了，全局事务还没决定时，任何普通读者都能看到减少后的
余额和已加的积分——这一点在 `atholdsThePointsRowForTheWholeTransactionAndTccDoesNot` 里于事务开着的
第 1 秒被断言，离它做决定还有 4 秒。

它保证的是**没有别的全局事务能碰到这个中间态**，因为全局锁会拒绝。这既是保证也是账单：

| 实测 | AT | TCC |
| --- | --- | --- |
| 第二个全局事务要同一行 | 被拒，随后 `Global lock wait timeout` | 通过 |
| 行被占多久 | 整个业务事务，含远程调用及其超时 | 到 Try 提交为止 |
| 模型要改什么 | 不用改 | 多一个 `frozen` 列，一个方法变三个 |

并且证明了那是"竞争"而不是"拒绝"：锁没了之后同一个请求就成功。

**对本库使用者的一个具体后果**：这个拒绝是 `QueryTimeoutException`，不是
`OptimisticLockingFailureException`，所以 `aipersimmon.ddd.cqrs.retry-on-conflict` 不认它、不会重试。
本篇把它显式关掉并写了理由——竞争搬到了拦截器看不见的地方，开着只会加上永远不触发的重试，还让人以为
这种情况被覆盖了。（这不是库的缺陷：库不知道 Seata 存在，而无条件重试 `QueryTimeoutException` 在一般
情况下是错的。）

## 5. AT 还是 TCC：一句能落地的判据

TCC 的三个方法不是往聚合上焊的管道，而是**聚合承认"已预留"属于它自己的语言**。`PointsAccount` 之所以
有 `frozen`，因为"已承诺的积分"是关于积分的真实事实——业务能看见它、能统计它、能拿它讨论。`Account`
不需要任何新东西，因为余额就是余额，"已扣但还不确定"不是事务管理器之外任何人想谈的状态。

所以判据不是吞吐也不是优雅：**业务本来有没有这个中间态的词？** 有（预留/冻结/待确认/已授权），说明这个
状态本来就该在模型里，TCC 只是逼你补上；没有，那就是在用 TCC 造一个假的，而 AT——把中间态放在锁里而
不是模型里——是诚实的选择。竞争程度是决胜局，不是首要问题。

XA 为什么不在本篇主线：它需要驱动支持两阶段提交，并且把**数据库锁**持有到全局事务结束，在这个场景里
严格差于 AT（AT 只在 TC 侧持有逻辑锁，本地事务已经提交）。它的取舍是数据库层面的问题，不是建模问题。
Seata Saga 是 JSON 定义的状态机引擎，竞争对手是 S9 的 process manager，不是本篇。

## 6. TCC 的三个坑，落在模型里而不是框架里

| 坑 | 长什么样 | 在哪解决 |
| --- | --- | --- |
| 幂等 | Seata 会重试 Confirm/Cancel 直到成功 | 每个方法都按 reference 定位，返回 outcome |
| 空回滚 | Cancel 到了而它的 Try 从没跑过 | `cancelReservation` 容忍——**并且仍然写一条标记** |
| 悬挂 | Try 在它自己的 Cancel **之后**才到 | `reserve` 拒绝已取消的 reference；没有上面那条标记就做不到 |

被漏掉的总是那条标记。没有它，一个迟到的 Try 会冻结一笔永远等不到 Confirm 也等不到 Cancel 的积分，
而且没有任何地方报错。

## 7. XID 的不对称，以及两个守卫

AT 把 `TX_XID` 传给参与者。**TCC 故意不传**——TCC 分支是在调用方注册的，参与者那边的三个阶段是普通的
本地事务。把 XID 传给 TCC 阶段，同一次写入会**同时**变成一个 AT 分支，回滚时两套机制会按各自对"撤销"
的理解各撤一次。

一个 header，两个协议，要求相反。所以两端各自拒绝对方的上下文：`POST /awards` 拒绝**没有** XID 的请求，
`POST /reservations` 及其结算拒绝**带** XID 的请求，都是 409。

这两个守卫不是装饰。实测（§9 控制 1b）：把 header 去掉、再把守卫拆掉，**正常路径照样通过、没有异常、
没有日志**，而回滚会把钱退回去、把积分留下。守卫把这件事变成一个调用方不可能忽略的 422。

## 8. 两个 Spring Boot 应用共用一个 classpath（端到端模块的副产品）

为了断言"两个库里的两行同成同败"，端到端模块在一个 JVM 里启动两个服务。这暴露了三个独立进程会掩盖的
冲突，三个都在服务侧改掉而不是在测试里绕开：

| 冲突 | 修法 |
| --- | --- |
| account 应用的 base package 是 `..s10`，把 points 服务的 bean 一起扫进来了 | 每个应用扎在自己的上下文包 |
| 两边都带 `application.yaml`，一个赢，另一个静默用了邻居的 datasource | 改名 `account-service.yaml` / `points-service.yaml`，由各自的 `application()` 指定 |
| 两边都带 `db/migration/V1__*.sql`，Flyway 报 "Found more than one migration with version 1" | 分成 `db/migration/account` 与 `db/migration/points` |

另外一个在 harness 自己身上：`SpringApplicationBuilder.properties(...)` 落在 `defaultProperties`，
优先级**低于**应用自己的 yaml，所以这样传的覆盖会被读取、被忽略、且无法察觉。改成命令行参数。

harness 唯一不忠实的地方也写在它的 javadoc 里：两个应用在一个 JVM 里共享 Seata 的 TM/RM 单例，所以
harness 给两个上下文同一个 application-id，这是部署不会说的谎。它掩盖了什么（协调者区分两个应用相关的
行为、一个进程死另一个活的故障）与它仍然证明了什么（header、分支、undo log、锁、回滚、TCC 三阶段），
都逐条列出。

## 9. 负向对照（五个，逐个单跑）

| # | 改动 | 实测 |
| --- | --- | --- |
| 1a | AT 客户端不发 `TX_XID` | 恰好 2 红，都是响亮的 422 `banking.points-refused`，什么都没写 |
| 1b | 同上 + 拆掉参与者的守卫 | 1 红，**而且正常路径照样通过、零异常零日志**；回滚退钱留分。这就是守卫存在的理由 |
| 2 | points 服务 `enable-auto-data-source-proxy: false` | 恰好 2 红。同样的数据损坏但换了原因——而且是 XID 守卫**抓不到**的那种（XID 在，只是 DataSource 没被代理），points 侧一条 undo log 都不写 |
| 3 | `@BusinessActionContextParameter` 只放在接口上 | 恰好 2 红。Java 不继承参数注解：action context 丢掉所有业务值，Confirm 在缺值处抛 `NumberFormatException`，协调者每秒重试一次直到永远，而账户行一直被锁。是重试风暴而不是错误 |
| 4 | `reserve` 不再拒绝已取消的 reference | 恰好 2 红（1 单测 + 1 端到端），悬挂坑，最便宜那层先抓到 |

## 10. 库的问题：没有

本轮没有发现库的缺陷，这本身是本篇的结论之一：**在一个第三方分布式事务框架下，
`MybatisPlusAggregateRepository`、乐观锁拦截器、租户行拦截器、命令总线的事务边界全部不需要改动，
也不需要知道 Seata 存在。** 唯一需要写进文档的交互是 §4 里 `retry-on-conflict` 认不出全局锁超时，
而那是使用者的配置决策，不是库的 bug。

## 11. 没做的事

| | |
| --- | --- |
| XA / Saga | 见 §5，都写了"为什么不是本篇" |
| 协调者 `store.mode=db` / `raft` | compose 用文件存储并写明代价：协调者重启会丢在途全局事务，留下半成品和还没释放的锁。是部署问题，没有建模内容 |
| 全局事务里的 outbox 行 | **故意不做，且这是一条规则而不是省略**：AT 分支里写的 outbox 行会被回滚删掉，但期间轮询到它的 relay 已经发出去了。外发必须以全局提交为准，而这件事本库和 Seata 都不替你做——所以"全局事务 + outbox"是要避开的组合，不是待补的功能 |
| 进程在回滚中途崩溃 | 一个 JVM 没法诚实地测。协调者会重试，锁一直在，那一行在重试成功前不可写——这是 AT 要预案的故障形态 |
