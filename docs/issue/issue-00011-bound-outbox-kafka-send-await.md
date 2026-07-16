---
id: issue-00011-bound-outbox-kafka-send-await
type: issue
role: main
status: resolved
parent: issue-00003-messaging-delivery-reliability
---

# outbox relay 等待 Kafka 发送无超时:单条 stall 即永久卡死 relay 线程并击穿 ShedLock 租约

[[issue-00003-messaging-delivery-reliability]] 的生产侧加固让 `OutboxRelay` 单线程、逐条**阻塞等 ack** 后再
标记 `sent`(at-least-once)。但 `KafkaOutboxDispatcher` 等待 ack 用的是**无超时**的 `Future.get()`——一旦某次
发送迟迟不返回(broker 分区不可写、metadata 拉取卡住、网络黑洞),relay 线程就**无限期**卡在该条上。

## 问题(现状,file:line 为证)

- **等级:Medium**。
- `KafkaOutboxDispatcher.dispatch()`(`aipersimmon-ddd-messaging-kafka/.../KafkaOutboxDispatcher.java:52`)
  `kafkaTemplate.send(record).get()` **无 timeout 参数**。Kafka producer 的 `send()` 先受 `max.block.ms`
  约束、返回的 future 再受 `delivery.timeout.ms` 约束,但二者都是 producer 配置;若被调高、或遇到 future 迟迟不
  完成的退化路径,`get()` 就长时间(以致实际上无界)阻塞。
- relay 是 `@Scheduled(fixedDelay)` 单线程,方法**不返回下一轮就不触发**
  (`aipersimmon-ddd-outbox-jdbc/.../OutboxRelay.java:97-121`;mybatis 版同构)。因此单条 stall 的后果是
  **该实例的 relay 永久卡死**——不仅这一批,后续所有投递都停摆。
- 跨实例:relay 由 ShedLock 保护,`lockAtMostFor` 默认 `PT10M`
  (`OutboxRelay.java:100`、autoconfig `AipersimmonDddOutboxJdbcAutoConfiguration.java:106`)。卡死超过 10min
  后锁租约到期,**另一实例拿锁并发 relay**,与仍卡住的实例同时投递同一批未标记 `sent` 的行 → 在 broker 已经
  不健康时再翻倍压力,并可能撞死信表 `event_id` UNIQUE 而抛错中断整批。
- 次生放大:即便每条都有界(下述修复后),`batch-size`(默认 100)条在一次 poll 内串行、每条最多等
  `send-timeout` → 全批 stall 时单轮墙钟 ≈ `batch-size × send-timeout`,仍可能逼近/超过 `lockAtMostFor`。属**运维
  调参**边界,非本 issue 主修点(见「边界」)。

一句话:等待 ack 无上限,把「一条发不出去」放大成「整个实例的 outbox 停摆 + 跨实例重复投递」。

## 根因(第一性)

relay 用**同步阻塞**换取「ack 成功才标记 sent」的可靠语义,这条阻塞就必须**有界**——线程是稀缺且被
`fixedDelay` + ShedLock 隐式假设为「会按期返回」的资源。无界 `get()` 违背了这个隐式契约:把一次 I/O 的不确定性
升级成对**调度节拍**与**分布式租约**的破坏。修复要把「等多久」变成一个显式、可配、有上限的决策,并把超时归入
**瞬时失败**(留在 outbox、下轮按退避重试),复用既有可靠性机制而非新造一套。

## 复现

`KafkaOutboxDispatcherTest`(纯单元,mock `KafkaTemplate`,无 broker):`send()` 返回一个**永不完成**的
`CompletableFuture`,`assertTimeoutPreemptively` 在旁线程运行 `dispatch()` 并在超时后强制中断。

- **修复前**(无超时 `get()`):`dispatch()` 永不返回,`assertTimeoutPreemptively` 触发 → 测试 **红**,坐实
  「relay 线程被无限期占用」。已实际跑出:线程 park 在 `CompletableFuture.get()`
  (`KafkaOutboxDispatcher.java:52`),`execution timed out after 3000 ms`。

## 修复

把「等待 ack」变为**有界**并将超时并入既有的瞬时失败通路:

1. `KafkaOutboxDispatcher` 新增 `sendTimeout`(`Duration`),`get()` → `get(timeout, MILLISECONDS)`;捕获
   `TimeoutException` → 尽力 `future.cancel(true)` → 抛 `IllegalStateException`。该异常经
   `DefaultFailureClassifier` 判为**瞬时**(非永久集内)→ relay 留行、下轮按退避重试(at-least-once 不变)。
2. 新增配置 `aipersimmon.ddd.messaging.kafka.producer.send-timeout-ms`(默认 30000)。保留 2 参构造(委托默认值)
   以不破坏既有装配/测试。autoconfig 注入该值。
3. 效果:单条 stall 在 `send-timeout` 内返回 → relay 线程必然按期释放、`fixedDelay` 继续、每轮 poll 墙钟有界
   → 既治「实例永久卡死」,又消除「卡死 > 租约 → 跨实例并发」的主因。

## 边界

- **不改**逐条阻塞 + 单线程 relay 的模型(那是保序 + at-least-once 的既定取舍,见 issue-00003 / issue-00007)。
- 全批 stall 时 `batch-size × send-timeout` 仍可能逼近 `lockAtMostFor`——这是**调参**关系(下调 `batch-size`、下调
  `send-timeout`、或上调 `lock-at-most-for`),在配置 Javadoc 与本 issue 记明,不在本次以「每轮墙钟预算」等更大改动
  兜底(可作后续)。实践中失败行首轮即被推入 `next_attempt_at` 未来、下轮不再入选,批次会快速收缩。
- 与 inbox 幂等、消费侧 DLT 正交。

## 影响模块

`aipersimmon-ddd-messaging-kafka`:`KafkaOutboxDispatcher`、`KafkaMessagingProperties`(新增 `producer.send-timeout-ms`)、
autoconfig 装配。无 schema / 无 outbox 表结构变更。

## 验收标准(GWT)

- **AC-1**:当 broker 迟迟不 ack(future 不完成),`dispatch()` 在 `send-timeout` 内抛 `IllegalStateException`
  而非无限阻塞(单元测试以 `assertTimeoutPreemptively` 证明有界)。
- **AC-2**:该超时被判为瞬时失败——relay 不死信、行留 `sent=false` 并按退避于下轮重试(复用既有分类/退避)。
- **AC-3**:正常发送(future 及时完成)行为不变;既有 `KafkaOutboxDispatcherTest` 全绿;autoconfig 装配可覆盖。
- **AC-4**:`send-timeout` 可通过 `aipersimmon.ddd.messaging.kafka.producer.send-timeout-ms` 配置,默认 30000。

## 验证结果

先复现后修,库反应堆(`aipersimmon-ddd-messaging-kafka`)全绿。

- **落地**:
  - `KafkaOutboxDispatcher` 新增 `Duration sendTimeout` 字段与三参构造(保留二参构造委托默认
    `DEFAULT_SEND_TIMEOUT = 30s`);`send().get()` → `send.get(sendTimeoutMillis, MILLISECONDS)`;新增
    `catch (TimeoutException)` → `future.cancel(true)` + 抛 `IllegalStateException`(不在
    `DefaultFailureClassifier` 永久集内 → 判为瞬时)。
  - `KafkaMessagingProperties` 新增 `producer.send-timeout-ms`(默认 30000)及 Javadoc(记明
    `batch-size × send-timeout` 应低于 `relay.lock-at-most-for`)。
  - autoconfig `kafkaOutboxDispatcher` 注入 `Duration.ofMillis(producer.send-timeout-ms)`。
- **测试**:`KafkaOutboxDispatcherTest#doesNotBlockTheRelayThreadForeverWhenTheBrokerNeverAcknowledges`:
  `send()` 返回永不完成的 future,注入 200ms `sendTimeout`,`dispatch()` 在 `assertTimeoutPreemptively(3s)`
  内抛 `IllegalStateException`。修复前红(3s 强制中断)、修复后绿(~200ms 抛出)。同一红→绿转换即 AC-1/AC-2。
- **回归**:`KafkaOutboxDispatcherTest`(3)、`AutoConfigurationWiringTest`(6,含 dispatcher 装配)、
  `KafkaErrorHandlerTest`(4)、`KafkaIntegrationEventListenerTest`(8)、`KafkaDeadLetterIntegrationTest`
  (1,Embedded Kafka 端到端)全绿,`BUILD SUCCESS`。正常发送路径不变(AC-3),`send-timeout-ms` 可配(AC-4)。

AC-1 ~ AC-4 达成,本 issue `resolved`。

## 关联

- [[issue-00003-messaging-delivery-reliability]] —— 本 issue 补齐其生产侧「等待 ack 必须有界」的遗漏。
- [[issue-00007-ordering-across-backoff-window]] —— 同为 relay 侧加固;超时归入瞬时后走同一退避/保序通路。
