---
id: analysis-00025-samples-integration-events-across-services
type: analysis
status: draft
informs: [analysis-00014-ddd-samples-scenario-catalog]
---

# S4 集成事件跨服务：outbox 发布 + Kafka + inbox 消费

对应 sample：`aipersimmon-ddd-samples/s04-integration-events-across-services`（两个服务模块）。
场景清单见 [[analysis-00014-ddd-samples-scenario-catalog]]。

**本篇尚未覆盖寄宿的 S13（多租户端到端）与 S15（跨边界一条 trace）**，它们是下一个增量。

## 0. 本篇定位

一个业务事实要通知**另一个部署单元**。发布方必须保证"业务变更落库"与"事件必达"绑在一起；消费方必须在
at-least-once 投递下保证只生效一次。这是全部 sample 里最大的一篇，也是最容易写出"看起来对、实际静默不
生效"的一篇——本篇自己就写出了一个，靠测试抓回来（§4）。

## 1. 双服务不共享契约 jar

两个服务各自声明自己的事件类，**没有第三个模块放公共契约**。

| | 发布方的类 | 消费方的类 |
| --- | --- | --- |
| `@EventType` | `com.example.samples.ordering.OrderPlaced` v1 | 同一个 |
| 字段 | `orderId`、`customerId`、`lines` | `orderId`、`lines` |

它们能对上,是因为线上身份是 `@EventType` 的 `(name, version)` **逻辑对**,而不是 Java 类名——库为此
明确禁止了类名兜底（`IntegrationEvent.eventTypeOf` 的 javadoc：类名是实现细节,不是发布契约）。

共享 jar 会把**部署边界刚拆掉的编译期耦合**装回来：改一次两边必须一起发版,而这正是引入 broker 要消除
的东西。消费方只声明它真正读的字段,这同时解释了为什么**加字段是兼容变更、删字段不是**（演进本身是 S21）。

## 2. 发布侧：publish 是一次数据库写

`PlaceOrderHandler` 只有两行有意义的代码：存聚合、发事件。四件事值得说：

- **`IntegrationEvents.publish` 在这里不碰 Kafka**——classpath 上有 outbox 时,它在**当前事务里插一行**。
  于是两者一起提交或一起没有,消灭了两个事后无法修的故障：**没人被通知的订单**、**通知了但不存在的订单**。
- **handler 分辨不出自己用的是哪种传输。** 同一行代码,在进程内投递、relay 到 Kafka、或换别的传输下都
  一样。所以"以后再加 broker"是一个依赖加一个 topic 名,不是重写。
- **传 `context` 不是仪式。** 事件继承命令的 `correlationId`、把命令的 `messageId` 记成自己的
  `causationId`,于是三跳之后库存服务做的那次预留仍然带着最初那个 HTTP 请求的 id。这些**都不进 payload**。
- **聚合不发布任何东西。** 对外说什么是应用层的决定,聚合不该知道published contract 的存在。

测试把前两条钉成事实：`theOrderRowAndTheOutboxRowCommitTogether`,以及
`afailureAfterTheHandlerLeavesNeitherRow`——后者用一个 order 250 的拦截器在 handler **返回之后**抛异常
（事务拦截器是 200,所以还在事务内),两行一起消失。

### 2.1 什么值得跨服务

`@Externalized` 是**逐事件 opt-in**。sample 故意放了两个事件走同一条代码路径：

| 事件 | 注解 | 结果 |
| --- | --- | --- |
| `OrderPlaced` | `@Externalized("${ordering.events-topic}")` | 上 broker |
| `OrderDrafted` | 无 | 只在进程内,永不上 broker |

`aneventWithoutExternalizedNeverReachesTheBroker` 断言了后者：**装了 Kafka 不等于所有内部信号都上线**。
判据也就这一句：**这个事实是不是另一个进程的口粮**。草稿不是。

### 2.2 事件何时真正离开,顺序保证到什么粒度

`nothingLeavesTheServiceUntilTheRelayRuns`：POST 之后 outbox 行是 unsent、topic 上什么都没有。**发布
（业务语义）与投递（传输）在时间上是分开的**,这正是 outbox 买到的东西。

顺序：`IntegrationEvent.subject()` 返回聚合 id,它成为**分区键**（测试断言 `ce_subject == orderId` 且
`record.key() == orderId`）。Kafka 只在分区内保序,所以**保证的粒度就是"一个聚合"**——返回 null 会退化
成按 event id 分散,一个聚合的事件就跨分区了、顺序就没了。

## 3. 消费侧：桥已经替你去重了（本篇最大的一课）

`ReserveStockHandler` 原来自己查了 `Inbox`。结果是**每条消息都被静默跳过**：

```
消息被消费 ✓   inbox 行写了 ✓   库存没动 ✗   无异常、无死信、无日志
```

原因在 `KafkaIntegrationEventListener:152`：**消费桥自己就用 `(ce_source, ce_id)` 查 inbox**,并在
本地发布事件**之前**丢掉重投。handler 再查一遍,永远撞上桥刚写下的那条记录。

所以规则要写清楚：

| 传输 | 谁负责去重 |
| --- | --- |
| 库自己的消费桥（本体系事件格式） | **桥**。handler 不要再查,查了就等于全部跳过 |
| 自己写的适配器（第三方系统报文,S5） | **handler**,在命令事务内调 `Inbox`,让记录与效果同生同死 |

第二行才是 `Inbox` javadoc 那句 "Call it inside the same transaction as the processing" 的适用场合。

### 3.1 去重键是一对,不是一个

`twoProducersThatMintedTheSameIdAreNotMistakenForEachOther`：同一个 `ce_id`、不同 `ce_source`,两条都
被处理（预留 2 + 3 = 5,inbox 两行）。只按 id 去重的话,第二条会被当成幻影重复**静默丢掉**——所有人都用
UUID 时零代价,有人开始用 per-source 序号时当场出事。

### 3.2 毒丸的两种形态

| 记录 | 结果 |
| --- | --- |
| `ce_type` 本地不认识 | **当场死信**,不重试（重试也变不出一个本地类） |
| `ce_type` 认识但 `ce_dataschemaversion` 不认识 | **同样死信**——解析是精确的 `(name, version)` 对,**没有跨版本隐式回退** |

第二条是 S21 的入口：一个消费方还没适配的 payload 版本,宁可死信,也不会被误读成 v1。

库的三档失败策略（毒丸立即死信 / systemic 无限重试且永不死信 / 其余有界退避后死信）在
`CONFIGURATION.md` 的 `messaging.kafka` 一节,本篇不重复。**必须记住的运维前提**：`<topic>.DLT` 要自己
建。库不建、也故意不探测；缺了它,毒丸死信失败 → error handler seek 回去 → 该分区**永远重试同一条**,而且
因为是 producer 错误、不是 `DataAccessException`,那条 systemic 停滞的 WARN 不会响——唯一能看出来的信号
是消费延迟。

## 4. 本地降级与 fail-loud

**降级**：桥把消费到的记录**通过 Spring 的发布器在本地重发**,所以入站适配器那段代码在"有 broker"和
"同进程直递"下完全一样,一个分支都不用写。

**fail-loud**：`@Externalized` 事件 + 非 durable 发布器 = 启动失败（`AipersimmonDddMessagingKafkaAutoConfiguration:149-163`）。
理由是那种丢失**不可见**：事件在本地投递完就永远不出 JVM,没有异常、没有死信、没有消费延迟可告警。

但这条 guard 曾在**只消费的服务**上误报——消费方为了订阅必须带 `@Externalized`,于是被判成发布方,
sample 只能给 inventory 加一个它永不写的 outbox。
[[issue-00161-the-publisher-guard-misreads-a-consumer-as-a-publisher]] **已修**:messaging 模块新增
`publishes-externalized-events`（默认 `true`,保持严格）,只消费的服务写一行 `false`。inventory 的
outbox 依赖、`flyway.components` 里的 `outbox`、以及那句 `relay.enabled: false` 全部删掉了——三张永不
写入的表不再出现在一个从不发布的服务里。**默认仍是严格的那一侧**,因为真发布方少了耐久传输就是不可见
的丢失。

## 5. 框架表从哪来

两个服务都显式列出允许框架创建什么——**在 classpath 上不等于会被建**：

```yaml
aipersimmon.ddd.flyway.components: [outbox]          # ordering
aipersimmon.ddd.flyway.components: [inbox, outbox]   # inventory（outbox 是被 §4 那条 guard 逼的）
```

忘了写不是静默的：每个组件自己的 schema 校验器会在启动期拒绝,并点名迁移路径与这个属性。

## 6. 换传输：该实现哪个接缝

库**没有锁死 Kafka**。两个层次可替换：

| 想换什么 | 实现哪个 |
| --- | --- |
| 只换 broker（RabbitMQ / RocketMQ） | `OutboxDispatcher`（relay 把待发行交给它）+ 一个自己的入站桥 |
| 整个发布语义 | `IntegrationEvents`（若要 durable 语义,还要实现 `DurableIntegrationEvents` 标记，否则 §4 的 guard 会拦） |

Kafka 那套本身就是这么接进去的（`KafkaOutboxDispatcher` 是一个 `OutboxDispatcher`）。本篇不实现第二种
broker——那属于"证明接缝存在"而不是"演示 DDD 流程"。

## 7. 尚未覆盖但必须写下的前提：offset 重置

`inbox.cleanup.retention-seconds` 默认 30 天,含义是**必须长于最长可能的重投延迟**。一次
`reset offset to earliest` 直接打破这个前提：很久以前的事件被当成新消息重放,而 inbox 里的键早已被清掉,
于是**重复生效**。

所以运维上要成对配置：**topic 保留期 ≤ inbox 保留期**。否则 broker 还留着的记录,inbox 已经忘了。历史事
件要不要补发是业务决定,但"补发一定会被重复处理"这件事必须先知道。这条本篇**没有做成测试**——它需要一个
inbox 保留期会尊重的时钟,留给 S22 运维面。

## 8. 测试手艺：三个"因为错误的原因而绿"的坑

都真实发生过：

| 坑 | 症状 | 修法 |
| --- | --- | --- |
| `@ServiceConnection` **不覆盖属性**,它提供 `KafkaConnectionDetails` bean | 测试消费者连了 yaml 里那个不存在的地址 → 永远拿不到分区 → 所有"断言没有消息"假绿 | 注入 `KafkaConnectionDetails` |
| 订阅 ≠ 分配 | produce 早于组加入,同一个测试连着两次跑一次绿一次红 | `ContainerTestUtils.waitForAssignment` + 断言 assignment 非空 |
| topic 不是队列 | "DLT 上有记录"因为**别的测试**的记录而通过 | 每个断言都按 key 点名自己那一条 |

一条通则：**空断言的价值等于"仪器是活的"这个证明的价值。** 没有那个证明,`isEmpty()` 只是在描述一个坏
掉的消费者。

## 9. 常见错法

| 错法 | 后果 |
| --- | --- |
| handler 里再查一遍 inbox（库的传输下） | **每条消息都静默跳过**,无异常无日志 |
| 只按 `ce_id` 去重 | 两个生产者撞 id 时静默丢消息 |
| 共享契约 jar | 编译期耦合回来,两边必须一起发版 |
| 用 Java 类名当线上身份 | 重构即破契约（库直接禁掉了这条路） |
| `subject()` 返回 null 还期待有序 | 一个聚合的事件跨分区,顺序没了 |
| 装了 broker 就以为事件都上线 | `@Externalized` 是逐事件 opt-in |
| 有 `@Externalized` 没 outbox | 启动失败——好事,因为那种丢失不可见 |
| 忘了 `flyway.components` | 启动期被组件的 schema 校验器拒绝（好）——不是运行时才炸 |
| 不建 `<topic>.DLT` | 毒丸死信失败 → 该分区永远重试,且 systemic WARN 不响 |
| `reset offset to earliest` 而不看 inbox 保留期 | 老事件被当新消息重放并重复生效 |
| 期待跨版本隐式回退 | 不存在,`(name, version)` 是精确解析,不认识就死信 |

## 10. 本篇不覆盖

- **S13 多租户端到端、S15 跨边界一条 trace**——寄宿本篇,下一个增量；
- 两个服务同时启动跑完整链路（当前两侧各自对着 wire contract 测）；
- offset 重置与保留期的测试（§7）——S22；
- 第二种 broker 的实现（§6 只点名接缝）；
- 契约演进与多版本共存——S21；
- 非本体系格式的入站报文——S5。
