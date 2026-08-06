---
id: issue-00057-unlimited-systemic-retry-is-invisible
type: issue
status: resolved
blocks: [report-00001-ddd-framework-review]
---

# 被判为 systemic 的消费失败无限重试却从不说出原因：卡死的分区和空闲的分区在日志里长得一样

## 问题（现状，file:line 为证）

- **等级：High（可服务性缺陷。不丢数据、不错处理，但把一个「消费者永久停摆」的故障变成运维无法发现、
  发现后也无法定位的状态）**。
- `AipersimmonDddMessagingKafkaAutoConfiguration.java:322-327` 为 systemic 失败装配
  `FixedBackOff(systemicBackoffIntervalMs, UNLIMITED_ATTEMPTS)`，并在 Javadoc 中明确其设计意图：
  「retried **indefinitely** … and **never** dead-lettered。The partition waits at the record until
  recovery」。该取舍本身是对的（不把健康消息冲进 DLT，保住 per-aggregate 顺序）。
- 但这条无限重试路径**永远不报告它在重试什么、为什么**。默认日志级别下，
  [[issue-00056-kafka-tests-pin-a-stale-inbox-schema]] 的排查里 20 秒无限重试期间，应用层输出只有两行 INFO，
  每 10 秒重复一次：
  ```
  INFO o.a.k.c.c.i.ClassicKafkaConsumer      : Seeking to offset 0 for partition it-events-0
  INFO o.s.k.l.KafkaMessageListenerContainer : Record in retry and not yet recovered
  ```
  **无 WARN、无 ERROR、无异常类名、无 topic-partition-offset、无 ce_id。** 两行都来自 Spring Kafka 容器，
  框架自己一个字都没说。
- 对照：走 bounded backoff 的失败最终 recover 到 `DeadLetterPublishingRecoverer`，那条路径有 ERROR 与 DLT
  记录，是**可见的**。恰恰是「永不结束」的那条路径完全静默。

后果：消费者永久停摆的现场表现是「什么都没发生」。运维看不到错误，只看到 lag 在涨；就算注意到那行
`Record in retry and not yet recovered`，它既不说是哪个异常、也不说是哪条记录——从这行日志到「inbox 表少了
一列 `tenant_id`」之间没有任何可追溯的链路。这正是 issue-00056 能在仓库里长期红着而根因不明的原因。

## 根因（第一性）

1. **观察 vs 期望**：期望「无限重试是一个响亮的、持续报告的降级状态」；实际「它与健康空闲不可区分」。
2. **最小机制**：`buildErrorHandler` 只通过 `setBackOffFunction` 决定**重试多久**
   （`AipersimmonDddMessagingKafkaAutoConfiguration.java:326-327`），而 `DefaultErrorHandler` 把
   「每次投递失败」的可观测性钩子放在另一个座上——`setRetryListeners(RetryListener...)`。框架从未装配它。
   于是失败的**分类结果**只影响了行为，没有产生任何**信号**。
3. **真根因**：框架把「无限重试」当成一种**策略**来实现，而它实际上是一种**运行时降级状态**。策略只需要
   配置；降级状态必须被声明出来。同一类错误在框架别处已有正确范式：issue-00044 的
   `aipersimmonDddDurableTransportGuard` 对「传输被漏配」是 fail-loud 的，issue-00053 的结论同样是
   「能力降级必须显式声明」。systemic 无限重试是**同一个模式的第三个实例，却仍是 fail-silent**。
4. **排除的伪根因**：
   - 不是「该把 schema 漂移改判为 permanent、送进 DLT」。把每条消息都因本机 schema 坏了而 DLT 掉，会在
     补上迁移后留下一堆需要人工回灌的死信，比等待恢复更糟。**分类是对的，缺的是声音。**
   - 不是「日志级别调到 DEBUG 就能看」。要求运维预先把生产调成 DEBUG 才能发现停摆，等于没有可观测性。
   - 不是 Spring Kafka 的问题。它提供了 `RetryListener` 座位，框架没有使用。

## 复现（test-first）

不用 broker：`buildErrorHandler` 是 package-private、可直接单测（`KafkaErrorHandlerTest` 已如此做）。

1. 用 `buildErrorHandler(recoverer, consumer)` 构造 handler，喂一个 `DataAccessException`。
2. **断言（现状 → 失败）**：`handler.getRetryListeners()` 为空 —— 无限重试路径上没有任何观测钩子，
   因此不可能产生日志。
3. 修复后：断言装配了一个 retry listener，且它在 systemic 失败时产生一条包含
   topic / partition / offset / 异常类名 的 WARN；非 systemic 失败不产生（那条路径已由 DLT + ERROR 覆盖）。

## 修复

在 `buildErrorHandler` 中装配一个 `RetryListener`，仅对**被判为 systemic**的失败在每次投递失败时打 WARN，
内容包含 topic-partition-offset、投递次数与异常（含 cause 链），并点明「该分区在此记录处等待，不会进 DLT」。

日志节拍问题的处理：**不引入独立的限流参数**。WARN 的频率天然等于 `systemic-backoff-interval-ms`
（默认 10s），即「重试一次就报告一次」。这让节拍成为运维已经在配的那个旋钮的函数，而不是第二个需要理解的
旋钮——嫌吵就调大 backoff，而调大 backoff 本来就是对持续故障的正确反应。

非 systemic 失败不打 WARN：那条路径以 DLT + ERROR 结束，再加一层只会制造噪声。

## 验证结果（已修复）

`AipersimmonDddMessagingKafkaAutoConfiguration.buildErrorHandler` 现装配
`SystemicStallReporter implements RetryListener`。原 `isSystemicFailure(Exception): boolean` 改为
`systemicCause(Exception): DataAccessException`——返回匹配到的异常而非布尔值，让报告能点名真正的 cause，
同时避免为了打日志再走一遍 cause 链。

新增两项测试（`KafkaErrorHandlerTest`，无 broker，直接驱动 handler）：

- `aSystemicStallIsReportedWithTheRecordAndTheCause` — 断言 WARN 含 `stalled at orders-3@42`（topic-分区-offset）、
  `evt-7`（ce_id）、`database is down`（cause），且第二次投递仍产出 `still stalled at ...`（不会在首次后转静默）。
- `anAmbiguousFailureIsNotReportedAsAStall` — 断言 bounded 路径**不**产生 stall 报告（它已由 DLT + ERROR 覆盖）。

**守卫有效性已实证**：临时注释掉 `handler.setRetryListeners(new SystemicStallReporter())` 后，
`aSystemicStallIsReportedWithTheRecordAndTheCause:174 names the stuck record ==> expected: <true> but was: <false>`
——即修复前该断言确实为假，这不是一个恒真测试。

框架全量 `mvn -f aipersimmon-ddd/pom.xml install`：**BUILD SUCCESS，742 项测试全绿**，质量门全部通过。

## 关联

- [[issue-00056-kafka-tests-pin-a-stale-inbox-schema]]（本 issue 的发现现场；那个 issue 长期不可诊断的原因）
- [[report-00001-ddd-framework-review]]（可服务性视角）
- [[issue-00044-integration-events-bypass-outbox-kafka-at-runtime]]（「可选能力缺席 → fail-loud」的正确范式）
- [[issue-00053-id-generator-silently-degrades-to-uuidv4]]（同一类「降级必须显式声明」的结论）
