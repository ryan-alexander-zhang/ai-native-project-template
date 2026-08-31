---
id: analysis-00035-samples-operability-deadletters-retention
type: analysis
status: draft
parent: analysis-00014-ddd-samples-scenario-catalog
---

# S22 运维面：死信、重放、保留清理与启动自检

对应 sample：`aipersimmon-ddd-samples/s22-operability-deadletters-retention`（两个服务：
ordering-service 发布并提供运维端点、inventory-service 消费；42 个用例）。场景清单见
[analysis-00014-ddd-samples-scenario-catalog](analysis-00014-ddd-samples-scenario-catalog.md)。

## 0. 本篇定位

前面每个 sample 演示的是"流程能跑"。本篇问的是"能不能被运行"：投不出去的消息去哪了、运维怎么把它拿回来、
哪张表会一直长、以及框架被要求做它做不到的事情时会不会出声。

## 1. 一个 broker 设置，没有它整篇都是假的

`StrictKafka` 用 `KAFKA_AUTO_CREATE_TOPICS_ENABLE=false` 起 broker。这不是细节，是本篇一半内容能不能被
观察到的前提：

| | 自动建 topic 开着（broker 默认，其余 sample 的环境） | 关掉 |
| --- | --- | --- |
| 往没建的 topic 发布 | 静默成功，建出一个没人消费的 topic | 失败，进死信 |
| 毒丸记录而 `<topic>.DLT` 不存在 | DLT 被那次投递自己建出来 | 恢复发布失败，分区原地永久重试 |

两个隐患在宽松环境里**都看不见**，在严格环境里都出现——而严格环境通常就是生产。跑在默认配置上的 sample
什么也证明不了，还会两次教错。

## 2. 发布侧：消息如何停止是"活的"

两种 reason，区别决定运维下一步做什么：

| reason | 本篇的成因 | attempts | 重放有用吗 |
| --- | --- | --- | --- |
| `RETRIES_EXHAUSTED` | 目标 topic 从没被建出来 | 打满上限（3） | 有用——**先把 topic 建了** |
| `PERMANENT` | 上一次部署退役了事件类，留下未发的行 | 1，不浪费重试 | 没用，除非先改点什么 |

**两个都不是注入的。** 第一个是真的 Kafka producer 在真 broker 上找不到 topic；第二个是直接往 outbox 写的
一行，因为"永久失败"的定义就是本地没有类能应答那个 `(type, version)` ——当前部署的代码不可能产出它。真实成因
是一次部署删掉了事件类而它的未发行还在表里，手写一行正是那次部署留下的东西。用 stub 让 dispatcher 抛异常，
测的就变成分类器而不是这件事。

有一处不对称值得带走：**被 `@Externalized` 的遗留行照样会发出去**，因为 broker 不需要你的类就能收下字节。
只有进程内那条腿必须把事件重建出来，所以发布方的永久失败只发生在出门前要解码的那一步。过 broker 的毒丸在
**消费方**才显形——那就是本篇的另一半。

## 3. 行是"搬走"的，这是个设计决定

用尽的消息在一个事务里从 `aipersimmon_outbox` **搬进** `aipersimmon_dead_letter`。在原行上打个"放弃"标记的
话，那行要么还可被选中（poll 每秒重试一个无望的行），要么变成写热表上的墓碑——而那张表每条命令都要 insert。
两种代价都由业务事务付。搬走意味着热表只装活的工作。

代价由测试说出来而不是藏起来：**放弃会把聚合放行。** 只有某个 subject 队首可被 claim，所以退役的行让它的
后继发出去——下游于是看到事件 2 而没有事件 1。反过来（把队列堵在毒丸后面）是用停滞换缺口，而单个聚合的停滞
在其余流量正常时是看不见的。两边都不免费，所以**一条死信就该报警**："一条消息被搁置"和"某个消费者对那个
聚合的视图现在是错的"是同一件事。

## 4. `/ops/dead-letters`：缺了它才是真 bug

三个路由：列表（游标分页）、按 id 查、重放。库把 `DeadLetters`（读）和 `DeadLetterStore`（存与重放）拆开，
正是因为 `replay` 要一个调用方**必须已经有**的 event id——而在读端口存在之前，唯一的来源是对一张应用并不
拥有的表手写查询。**带 outbox 而不带列表的服务，把消息隔离进了一间没有门的房间。**

不返回 payload。triage 问的是"为什么没发出去、值不值得重放"，载荷两个都答不上；而带上每条消息体的列表既贵，
又是把事件内容泄到运维屏幕上的一条路。

### 4.1 重放是个按钮，不是变更流程

库和这个服务都无法验证根因是否已经消失——任何代码都不能。所以正确的安排是让错误的重放**便宜**而不是不可能：
消息以 unsent、attempts 归零回到 outbox，同样地失败，回到同一张表。`ReplayAfterTheFixTest` 在一个方法里
走完整条 runbook——发布进虚空、看见放弃、**建 topic**、重放、看它到达——因为中间那步是唯一没法自动化的一步，
而两边的半截只有夹着它才有意义。

让"按两次"安全的不是这个服务：**事件保留它出生时的 id**，所以已经见过它的消费者靠 inbox 认出重复——就是那个
吸收 relay 自身 at-least-once 重投的 `(source, ce_id)`。一个铸新 id 的"重放"，是关于同一事实的第二个事件，
下游任何去重都抓不住。按第二次返回 404：靠后果幂等，不靠守卫。

### 4.2 `lastError` 曾经说不出的那一半 → issue-00165（已修）

实测且是真缺口：relay 只记最外层异常，所以最常见的发布失败读作
`org.springframework.kafka.KafkaException: Send failed` ——topic 名和真实原因
（`Topic … not present in metadata`、`UnknownTopicOrPartitionException`）在 cause 链下两层，被丢掉。
[issue-00165-a-dead-letters-last-error-drops-the-only-useful-half](../issue/issue-00165-a-dead-letters-last-error-drops-the-only-useful-half.md) **2026-08-04 已修**（新增
`FailureSummary` 有界摊平 cause 链，outbox relay 与 process-manager relay 两处共用）。

写这篇时 `DeadLetterTest` 断言的是**现状**，包括"topic 名不在其中"，而不是拿个子串糊过去——按
isolate-before-attributing-a-fix 的规矩，测试要能在修好之后反过来。**这条规矩当天就兑现了**：改完库，
那两条断言变红，改成正向断言即可，不靠任何人记得回来收尾。

## 5. 消费侧：分区拿一条处理不了的记录怎么办

三档，库拒绝把它们塌缩成一个重试策略：

| 失败 | 依据 | 重试 | 去处 |
| --- | --- | --- | --- |
| 毒丸——未知类型/损坏/解不开 | 异常类型 | 不重试，跳过 backoff | 立刻进 `<topic>.DLT` |
| 系统性——`DataAccessException` | 异常类型 | **无上界**，固定间隔 | 哪也不去，分区在原地等 |
| 其它 | —— | 有界 backoff | `<topic>.DLT`，兜底 |

单一策略必须选边，而两边各自在某处是灾难：有界重试后进 DLT 对坏记录是对的，对十分钟的数据库故障是灾难
——分区以重试速度把自己排空进 DLT，一次抖动变成"手工重放这期间到达的一切"，还得考虑顺序。无上界重试对故障
是对的，对坏记录是灾难。所以库只对唯一带确定性的信号声称确定，并**刻意接受那次停滞**。
`SystemicFailureTest` 量了两半，包括回报：**没人重放、没人重启、没人碰消费组；恢复就是下一次重试成功。**

## 6. 全篇最锋利的一对

`PoisonWithoutDltTest` 与 `PoisonWithDltTest` 跑的是**同一个应用、同一份配置、同一条记录**。唯一差别是
`<topic>.DLT` 在不在。

| | `.DLT` 缺失 | `.DLT` 存在 |
| --- | --- | --- |
| 毒丸记录 | 被永久重投 | 完整落在 DLT 上，`ce_` 头与异常都在 |
| 它后面那条健康记录 | 永不被消费 | 被消费 |
| 外部看到什么 | 一个活着、健康、且不动的服务 | 一条记录被搁置 |

**消费方的 topic 清单是"每个订阅两个 topic"**，而第二个的名字不在任何配置文件里——错误处理器自己拼出来的。
库刻意既不自动建也不探测（探测会在所有开自动建的环境里误报，在其余环境里也只能 warn），所以知道这件事是
部署的责任。

停滞是本篇最糟的失败，不是因为它停，而是因为**没有任何人在看的地方说出这件事**：健康检查过、消费组在、
单个分区的 lag 在长。**按分区的 consumer lag 才是把它变成一次 page 的那个告警**，这里没有替代品。

## 7. 保留清理：五个组件，三种默认

清单里写的是"四类框架表"。实际是**五个** migration 组件——`outbox`、`inbox`、`process-manager`、
`operation-log`、`web-store`——而且清理默认并不统一：

| 组件 | 清理默认 | 怎么确认的 |
| --- | --- | --- |
| `outbox`（已发送行） | **关** | `RetentionTest` 断言没有 `OutboxCleanup` bean；`PurgeTest` 打开它 |
| `aipersimmon_dead_letter` | **根本不清** | `PurgeTest`：那趟 sweep 不碰它 |
| `inbox` | **关** | `InboxRetentionTest`（测的是窗口，不是那个 job） |
| `process-manager` | 关 | 读源码：`AipersimmonDddProcessManagerAutoConfiguration.java:373-376` + `ProcessManagerProperties.java:150` |
| `operation-log` | 关 | 读源码：`AipersimmonDddOperationLogMybatisPlusAutoConfiguration.java:91-93` |
| `web-store` | **开**，每小时 | 读源码：`WebStoreCleanupProperties.java:17` |

前四个"关"是对的：删行不可逆，正确的窗口是部署的属性。web-store 是"开"，因为那些行带着 store 自己写的
`expires_at`——扫掉它们不会毁掉任何人可能想要的东西。这就是六个答案底下真正的那条规则：**保留策略可以默认
"开"，只有当行本身已经写明了自己何时不再重要。**

"关"的代价是：从不配置它的服务会让表一直长，而第一个发现的人是 DBA。默认关只有在"漏配是可见的"时才是好
默认，而对这些表而言那意味着一个指标——恰是本篇从内部给不出的东西（§10）。

### 7.1 inbox 的窗口是正确性设置

六个里唯一一个。inbox 的行不是"发生过什么"的记录，它是**让下一次投递变成 no-op 的那个东西**。清掉它，同一条
消息就不再是重复——它是一条内容相同的新消息。`InboxRetentionTest` 量出后果：**下三件却预留了六件，没有报错，
日志里什么都没有。**

所以窗口取的是"同一条消息还可能到达"的所有路径的最大值：broker 保留期，**加上**恢复期间被重置到最开头的
消费组，**加上**两周后被运维重放的一条死信，**加上**发布方自己卡住的 outbox。中间两项从消费侧根本看不见
——这正是"照着 `retention.ms` 定这个数"会错的原因。

## 8. 启动期：拒绝启动是便宜的那种失败

`aipersimmon.ddd.flyway.components` 才是把框架 migration 应用上去的开关，在 classpath 上不等于被应用。
漏一项是很普通的失误，但爆炸半径不普通：outbox 的 insert 在**业务事务里**，所以缺表不是"发布坏了"，是
**每一条会发布的命令都坏了**。

`StartupSelfCheckTest` 启三次，第三次才是论点：

| 配置 | 结果 |
| --- | --- |
| 组件清单为空 | 拒绝启动，消息里点名属性、手工替代方案、以及开关 |
| 列了组件 | 启动成功（对照——没有它，这个类测的是自己的脚手架） |
| 清单为空 **且** `schema-validation=none` | **启动成功**，然后每条命令回滚，报一张本服务开发者从没写过的表 |

第三种是活着的、所有探针都绿的、因为清单少一行而对业务端点返 500 的服务。启动失败会被发布流程挡住；这个
被客户挡住。

**顺便记一条脚手架教训**：这三次启动最初共用同一个数据库，于是"该失败的那次"启动成功了——因为兄弟用例先
启动过一次并留下了表。发现它的是**对照用例的红**。测试基座因此改成每次启动建一个新库
（`Boot.java` 的 javadoc 记了原委）。这是 isolate-before-attributing-a-fix 的反向：**一个"这个配置起不来"
的断言，没有兄弟断言"同一个应用能起来"就一文不值**——而这次正是那个兄弟暴露了共享状态。

## 9. 能力降级：框架做不到时说什么

| 缺的能力 | 会怎样 | 为什么是这个姿态 |
| --- | --- | --- |
| 有 `@Externalized` 但没有能出门的 transport | **启动失败**；一个属性可接受 | 没有任何可观察的东西会揭示这个损失 |
| 同上，开了那个属性 | 启动，每次启动 WARN | 绕路要成为"受支持的绕路"，且它每次都说清丢的是什么 |
| 边缘防护跑在内存 store 上 | 启动，WARN | 对单实例是正确的；框架看不见副本数 |
| 同上，`allow-in-memory-stores=false` | **启动失败** | 生产 profile 该带的那一行 |
| 没有 `IdGenerator` | 启动失败（引用源码，未测——bundle starter 让它在实践中够不到）| `CommandTransactionGuard.java:22` 写明了这个姿态 |

注意不对称：transport 那条**默认严格**、逃生口 opt-in；内存 store 那条**默认宽松**、严格 opt-in。两个默认
都站得住，也没有一条统一规则——但有一个统一的**问题**，这才是要带走的：

> **这个缺失，之后还有任何东西能发现它吗？** 不能，就必须是启动失败。能——一个指标、一次重复扣款、一行日志
> ——那么 WARN 加一个严格开关就够了，而拒绝启动只会教人去设那个开关。

"发布进死胡同"直接不通过这个测试：relay 会把每个事件标成已发送，所以没有异常、没有死信、没有 consumer lag。
从任何角度看发布方都体检合格，而下游只是再也没收到过你的消息。

## 10. 库的问题：一个新的，一个旧的，一处措辞

**新开 [issue-00165-a-dead-letters-last-error-drops-the-only-useful-half](../issue/issue-00165-a-dead-letters-last-error-drops-the-only-useful-half.md)（P2，可运维性）——当天已修**：
`OutboxRelay.java:472` 的 `summarize` 只取最外层异常，`ProcessEffectRelay.java:247` 同形状。修法就是复用
`DefaultFailureClassifier` 已有的有界走链，提成 `core.error.FailureSummary` 供两处共用（"两处各写一遍"正是
成因）。

**复现旧的 [issue-00161-the-publisher-guard-misreads-a-consumer-as-a-publisher](../issue/issue-00161-the-publisher-guard-misreads-a-consumer-as-a-publisher.md)——也已修**：
inventory-service 只消费不发布，却被守卫逼着带 outbox，并因此**被逼给三张永不写入的表跑 migration**
（`flyway.components` 里那个 `outbox` 就是这么来的）。这是它第四次被独立撞到（S4、S12、S21、S22），
**同一条 issue 复发四次本身就是优先级信号**。修法：messaging 模块新增
`publishes-externalized-events`（默认 `true` 保持严格），四个 sample 的迁就一起拆掉。本篇的 yaml 与 pom
现在只带 `[inbox]`，而"一个服务可以被逼着建它永不写入的表"这句话，从此只是历史。

**一处措辞**（不是缺陷）：库为 `send-timeout-ms` 给出的算术（"低于 `lease-duration` 的一半"）漏了
`max.block.ms`。`KafkaOutboxDispatcher.java:81-83` 的 deadline 是在 `send()` 返回**之后**算的，而元数据阻塞
发生在 `send()` **里面**——而"topic 不存在"恰好是阻塞在元数据而不是阻塞在 ack 的那种情况。默认值下算术仍然
安全（60s < 5min/2），所以只是文档没把它算进去；两个服务都显式设了 5s 并注明理由。

## 11. 五个负向对照（逐个单跑，逐个量）

| # | 改动 | 实测 |
| --- | --- | --- |
| 1 | `PoisonWithDltTest` 去掉 `.DLT` topic | 恰好 1 红，且红在"毒丸后面那条被处理"的 await——DLT 就是让分区继续走的那个东西 |
| 2 | 发布侧 broker 打开自动建 topic | 恰好 2 红，两个死信用例都超时：topic 被自动建出来就没有失败可 triage。永久失败的用例保持绿，反过来确认了两类失败互相独立 |
| 3 | 模拟故障改抛普通 `IllegalStateException` | 恰好 1 红：一条健康的 `OrderPlaced` 落到了 `.DLT` 上——分类靠异常类型，判错就把好活儿冲进隔离区 |
| 4 | `InboxRetentionTest` 不做那次清理 | 恰好 1 红：重投被去重、永远不会翻倍——清理才是重复生效的成因 |
| 5 | 运维 controller 直接注入 `DeadLetterStore` | 恰好 1 红（ArchUnit，3 处违规），规则不空 |

## 12. 没做的事

| | |
| --- | --- |
| 指标与告警 | 本篇能点名却填不上的缺口。库经 `OutboxObserver`/Micrometer 上报，导出端 S15 已覆盖；阈值（死信 > 0、按分区 lag、未发送行的年龄）是监控练习 |
| process-manager / operation-log 的保留 | 表不在本 sample 里；开关同形状，进程表归 S9 |
| web-store 的 sweep | 只引用它自己的默认值，不跑：S2 用 Redis（TTL，无需清理）、S7 用 JDBC store |
| 从消费方 `.DLT` 重放 | 发布方的重放是对自己表的端点；DLT 重放是 topic 到 topic 的搬运，授权故事不同 |
| 批量重放 | 刻意一条一条来。事故里"全部重放"是第二次事故的开头 |
| 给 `/ops` 加安全 | 拆成独立 controller 就是为了以后能加，但没有安全模块可加 |
| 迁移顺序 | 哪个 runner 先跑、怎么改上线中的表——S23 |
| 运维面的多租户 | S4/S13 的题。死信行带 `tenant_id`，运维该不该看见所有租户的是策略问题，本篇不答 |
