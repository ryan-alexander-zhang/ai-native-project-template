---
id: analysis-00024-samples-scheduled-and-batch-entries
type: analysis
role: main
status: draft
parent: analysis-00014-ddd-samples-scenario-catalog
---

# S11 非 HTTP 入口：定时任务 / 批处理（入口适配器总论）

对应 sample：`aipersimmon-ddd-samples/s11-scheduled-and-batch-entries`。场景清单见
[[analysis-00014-ddd-samples-scenario-catalog]]。

## 0. 本篇定位

前面所有 sample 的入口都是 HTTP。本篇是**入口适配器的总论**：业务动作由时间触发、由批处理触发、由
运维人员触发时，路径应该和 HTTP 完全一样——收敛到同一条命令通道，而不是绕过 application 层直改数据库。

这一篇的价值密度集中在"批"上，因为批处理是**唯一一类天然会诱惑人跳过领域模型**的入口：一条
`UPDATE ... WHERE due_at < now()` 比一千条命令快得多，而且看起来完全合理。

## 1. 入口形态一览

| 形态 | 本篇 | 完整处理 |
| --- | --- | --- |
| HTTP 请求 | `OrderController` | S1、S2 |
| 时间流逝 | `ExpiredOrderSweepScheduler` | 本篇 |
| 跨多个聚合的批 | `ExpiredOrderSweep` | 本篇 |
| 运维人员（有意为之） | `OperationsController` | 本篇 |
| 入站消息 | — | S4、S5 |
| 三方回调 | — | S7 |

它们的差别只在**到达的东西是什么**。没有一个持有规则；sample 另加一条 ArchUnit 规则禁止任何入口触碰
持久化层——而这条规则压力最大的地方恰好是定时器，因为"在 scheduler 里直接跑一条 UPDATE"是两行改动。

## 2. 定时器里该写什么

**找出目标集合，然后逐个发命令。**

sample 的反例 `BulkCloser` 就是那条 `UPDATE`，保留在生产代码里（不是藏在测试里），因为重点是**它才是
大多数人第一反应会写的东西**。它一次丢掉五样：

| 丢掉的 | 具体后果 |
| --- | --- |
| 规则 | `WHERE due_at < now` 不知道这单已经付款了。它会把已付款的单关掉，而本该拒绝的状态机从未被咨询 |
| 事件 | 什么都不发布，于是"关单后应该发生"的一切（释放库存、通知客户）**只对这批行不发生**——最难察觉的那种不一致 |
| 乐观锁 | `version = version + 1` 没有 `WHERE version = ?`，那不是并发控制，那是个计数器 |
| 失败隔离 | 整个积压是一条语句一个事务，一行坏掉，一行都没关 |
| 上限 | 它取到多少算多少，于是故障恢复后的第一轮就是锁表那一轮 |

sample 用两个测试把前两条钉成事实：同样的行、同样的时刻、同样的截止时间，`BulkCloser` 把已付款那单
关掉且不发任何事件，而 sweep 不会（`thebulkStatementClosesThePaidOrderTooAndTellsNobody` 与
`thesweepLeavesThePaidOrderAloneWhereTheStatementWouldNot`）。

**例外何时成立**：批量语句是**数据修复工具**，不是业务动作的入口。若某个业务动作真的因为体量必须写成
一条语句，那它跳过的规则与事件必须**在别处、有意地、书面地**被补上——而不是"反正跑得快"。

## 3. 扫描是咨询性的，聚合才是权威

这是本篇与 [[analysis-00022-samples-validation-layers]]（S19 的 `CommandPrecheck`）同形的一课。

`ExpiredOrders.findExpired` 返回的是"**刚才看起来**已过期"的 id 列表。从这次调用到某个 id 真正被处理
之间，世界会动：客户付款了、运维手工关了、另一个实例扫到了同一行。所以：

- 端口**只返回 id，不返回行**——从扫描把状态带进命令的批，就是拿着过期副本在做决定；
- `asOf` 是**传进来的参数**，端口内部不读时钟（于是测试能决定"现在"是几点）；
- 真正的判定在 `Order.close()` 的状态机里。

`anorderPaidBetweenTheScanAndItsCommandCountsAsSkippedNotFailed` 让付款发生在**第一条关单命令抵达
总线的那一刻**，然后断言：那单仍是 `PAID`，本轮 `closed=1 skipped=1`，且 `allSucceeded()` 为真。

## 4. 命令粒度：一单一命令

判据只有一句：**粒度就是事务边界，也就是失败与重试的单位。**

一单一命令 → 每次 dispatch 自己一个事务 → 1000 单里 37 单失败，963 单已提交。一批一命令 → 1000 单
回滚，然后**重试全部 1000 单**。

这条做了负向对照：给 `sweepOnce()` 加上 `@Transactional`，四个测试变红，报的是
`UnexpectedRollbackException: Transaction rolled back because it has been marked as rollback-only`。
**而且比"成功的也回滚了"更糟**：循环照样数出 3 次成功、照样返回了一份说成功的报告，然后提交阶段才抛
异常——因为内层的一次拒绝把这个合并事务标成了 rollback-only。**一轮先报成功、再整轮丢失**，这就是把批
塞进一个事务的真实失败形态。

顺带一句：这也解释了为什么"拒绝"不能用异常穿透整轮。sweep 逐单 `catch`，正是因为一单的结论不该决定
整轮的命运。

## 5. 部分失败、结果可见性与重试

批处理入口没有 HTTP 调用方在等响应，所以**它若不产出一份有人能读的结果，它就是不可观测的**——最常见
的坏掉的定时任务，是那种已经静默失败了六周的。

`SweepReport` 分三种结果，混淆任意两种都会毁掉可观测性：

| | 含义 | 谁关心 |
| --- | --- | --- |
| `closed` | 命令提交了 | 看板 |
| `skipped` | 聚合拒绝了——已付款/已关闭/已不存在/另一个实例先到 | 没人；这是咨询式扫描在正常工作 |
| `failures` | 真的坏了 | 值班的人 |

**把 skip 报成 failure 的任务，会训练它的运维忽略 failure。**（`ConcurrencyConflictException` 归
skip，理由同上：那是"别人先写了"，下一轮不会再看到它。）

**重试怎么发生？靠候选查询本身。** `thenextRoundRetriesWhatFailedAndNothingElse` 断言：上一轮失败的
那一单在下一轮被再次尝试，已关闭的三单**不再出现**——没有任何记账、没有任何队列。这条要写成设计约束：

> 候选查询必须是**对当前状态的谓词**，永远不要是一份需要有人维护同步的 id 队列。

前者天然幂等且自愈；后者会漂移，而且漂移的方向是"漏掉"。

**轮次要有上限。** `aroundIsBoundedAndTheBacklogDrainsOverRounds`：12 单、batch-size 5，四轮
依次 5 / 5 / 2 / 0。无上限的轮在正常日子里毫无问题，然后在故障恢复后的那一天锁表。

## 6. 没有 HTTP 请求时，上下文从哪来

定时线程**不继承**两样东西。

### 6.1 租户

`TenantContext` 在可信边界绑定（HTTP 过滤器、消息消费入口），定时线程两者都没有。sample 通过
`TenantContext.effective()` 读取，把"什么都没绑"的决定交给部署模式一次性回答：

- 多租户关闭 → `Tenants.ROOT` 哨兵（单租户就是 N=1 的多租户，每行仍有归属）；
- 多租户开启且什么都没绑 → 抛 `MissingTenantException`，而不是悄悄把一个租户的行当成另一个的扫掉。

两个测试分别钉住"未绑定时是哨兵"和"`runAs` 绑定后每条命令都带着 acme"。**多租户的定时任务因此是
"按租户循环、每个租户一轮绑定"**——因为"一次扫所有租户"根本不是一个租户作用域的读能表达的东西。

### 6.2 关联 id

`send(command)` 会为每单铸一个**全新的 root**，于是 1000 单留下 1000 条互不相关的 correlation 链，
没有任何办法问"03:15 那一轮干了什么"。

sample 的做法：**本轮自己铸一个 root 上下文，作为每条命令的 cause 传入**。

```java
CommandContext round = CommandContext.root(TenantContext.effective(), idGenerator.newId());
...
commandBus.send(new CloseExpiredOrder(orderId), round);
```

于是每条命令共享 round 的 correlationId、把 round 记为自己的 causationId，而**自己的 messageId 仍由
总线铸造**。`everyCommandOfOneRoundSharesOneCorrelationId` 三条都断言了。报告里回传 `runId` = 这个
correlationId，日志一查即可。

这是 `send(command, cause)` 的第三种正当用法（前两种是入站集成事件、handler 内的后继命令）：**批次
本身就是因果根**——它本来就是。注意这**不是** `sendAs`：身份仍由总线铸造，没有任何持久化的消息身份。

至于**操作者身份（actor）**：本篇不裁决。审计需要的可信操作者来源是 S14 的题目，而 S14 明确要覆盖
"没有 HTTP 请求的入口"这一情形。

## 7. 多实例互斥：不要锁调度，要让工作本身互斥

这是本篇最值得记住的一条，而且**答案来自库自己**。

库的 outbox relay 是一个真实的定时任务，它的 scheduler javadoc 写着：

> Every instance runs this schedule, and that is deliberate. Mutual exclusion is per row: a poll
> claims the rows it is going to dispatch and leases them... Guarding the schedule with a lock
> instead would put delivery behind a single holder — and an instance killed while holding it
> releases nothing, so every other instance would skip its poll, silently, until that lock expired.

也就是说，"加个分布式锁让只有一个实例跑"这个直觉，交换的方向通常是错的：它把**整条业务的推进**押在
一个持有者身上，而持有者被 kill 时什么都不释放。

于是两种正确形态，判据清晰：

| 工作的性质 | 互斥手段 | 参考 |
| --- | --- | --- |
| 对某一行的状态变更（可版本校验） | **什么都不用加**——聚合的乐观锁就是仲裁 | 本 sample |
| 没有可版本化的状态（发提醒、调三方） | **先声明领取，带可过期的租约** | 库的 `OutboxLease` / `RelayLeases` |

sample 证明了第一种：`twoInstancesSweepingAtOnceCloseEachOrderExactlyOnce` 让第二个实例从本轮第一条
命令抵达总线的那一刻起跑完整一轮（**另一个线程**，因为嵌套 dispatch 会加入当前事务而不是与它竞争），
然后断言每单恰好关闭一次、败者报 3 次 skip。**没有锁、没有租约、没有 claim 表**——S8 那一课的直接
应用。

第二种为什么不在本 sample 实现：库已经有了参考实现，在这里重写一遍会把它教成默认选择，而对一个可版本
校验的状态变更来说它不是。租约的关键性质照抄库的原话：owner 只用于诊断、**token 才是围栏**、
`until` 是"实例被 kill 也无需任何人察觉即可恢复"的那个机制。

## 8. 触发器与工作分离

`ExpiredOrderSweep` 上**没有 `@Scheduled`**；触发器是另一个 bean，且挂在 `@ConditionalOnProperty`
上。这是照抄库的 relay 拆法，理由也是库给的：触发器独立之后，"只在一个专用实例上跑"的部署、以及"自己
驱动"的测试，都只需要把触发器关掉然后直接调那个方法。

回报是直接的：

- 13 个 sweep 测试关掉触发器、直接调 `sweepOnce()`，语义确定、不等待；
- `ScheduleTest` 单独一个上下文打开触发器，用 `await()` 断言"没人调用它，单子也关了"——测的是**接线**；
- 运维入口只花了一个方法，因为工作从来没有和定时器焊在一起。

顺带一条测试纪律：等异步用 `await()` 而不是 `sleep`。固定 sleep 要么让套件变慢，要么让它变脆，通常
是先后两者都来。

## 9. 一个关于测试自身的发现（负向对照没咬住）

三次刻意破坏（均已还原）：

| 破坏 | 结果 |
| --- | --- |
| 删掉 `close()` 里的 `TRANSITIONS.check` | 两个测试红：已付款的单被关、两个实例都关掉了每一单 |
| 给 `sweepOnce()` 加 `@Transactional` | 四个测试红，`UnexpectedRollbackException` |
| 删掉扫描的 `ORDER BY payment_due_at` | **一个都没红** |

第三条是关于**测试**的发现，不是关于代码的。PostgreSQL 用 `(status, payment_due_at)` 索引来服务这个
查询，索引扫描顺序恰好就是断言要的顺序，所以那条断言**记录了意图但无法强制意图**——这个层级上没有测试
能做到，因为计划可以返回任意顺序，而当前计划出于查询没要求的原因返回了正确的顺序。

`ORDER BY` 保留，理由正是**计划不是契约**：换索引、加过滤、升级 planner，无序扫描就开始饿死积压的
尾部，而没有任何测试会发现。这条留在 sample 的测试注释里，因为"你必须写 ORDER BY，即使你的测试分辨
不出"本身就是一课。

## 10. 常见错法

| 错法 | 后果 |
| --- | --- |
| 定时器里直接跑 `UPDATE` | 规则、事件、乐观锁、失败隔离、上限一次全丢 |
| 一批一个命令 | 一单失败回滚全批，然后重试全批；报告先报成功再整轮丢失 |
| 整轮包一个事务 | 同上，且失败以 `UnexpectedRollbackException` 出现在提交阶段 |
| 把扫描结果当权威 | 关掉了刚刚付款的单 |
| 扫描返回聚合/行并带进命令 | 拿过期副本做决定 |
| 候选靠一份 id 队列而非状态谓词 | 队列会漂移，方向是"漏掉" |
| 轮次无上限 | 故障恢复后的第一轮锁表 |
| skip 与 failure 混为一谈 | 运维学会忽略 failure |
| 批处理不产出结果 | 静默失败六周 |
| 用分布式锁保护调度 | 整条推进押在一个持有者上；它被 kill 后所有实例静默跳过 |
| 定时线程不绑租户 | 多租户开启时抛（好），关闭时按哨兵跑（对）；但若代码自己 `Tenants.of` 猜一个就是数据隔离事故 |
| 每单 `send(command)` 铸新 root | 一轮留下 N 条互不相关的 correlation，无法回答"那一轮干了什么" |
| 定时器用 `sendAs` | 那是给有持久化身份的重投用的，定时任务没有 |
| `@Scheduled` 直接写在工作方法上 | 触发器与工作都变得不可测，运维入口也无处安放 |
| 用 `sleep` 等异步 | 慢，且脆 |

## 11. 本篇不覆盖

- 用租约领取工作的完整实现——库的 outbox relay 就是参考实现（S22 会从运维面再碰它）；
- 分布式锁选型（ShedLock 等）——第 7 节给了"通常是错的交换"的理由，库不要求任何一个；
- 多租户端到端（租户列、line 拦截器、传播）——S13，寄宿 S4；
- 操作者身份（actor）与审计——S14；
- 入站消息作为触发器，以及重投带来的"必须可重复"——S4、S5；
- 运维入口的鉴权——本 sample 没有安全层，假装有一个会教错东西；
- 分片/并行批处理——有上限的轮在这里，并行需要第 7 节的领取形态和足以支撑它的体量。
