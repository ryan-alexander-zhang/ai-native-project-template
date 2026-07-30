---
id: issue-00111-the-relay-waited-for-each-send-in-turn
type: issue
role: main
status: resolved
parent: report-00003-ddd-library-review-2026-07-29
---

# relay 逐条等 ack：每轮的代价是往返之和，而不是一次往返

## 症状

`KafkaOutboxDispatcher.dispatch` 对每条消息做 `send.get(timeout)`——写一条、等一条 ack、再写下一条。
于是一轮 poll 的耗时是**批内所有往返之和**。broker ack 按几十毫秒算，单实例上限约 100 msg/s；
一小时故障积压 180 万行，要排约 5 小时。

顺带还有两处同源的浪费：

- **producer 的批处理形同虚设**。Kafka producer 的 `batch.size`/`linger.ms` 靠"缓冲区里同时有多条记录"
  才起作用；写一条就阻塞等 ack，意味着每个 batch 里**永远只有一条记录**。
- **每条成功一次独立 `MARK_SENT`**。send 一旦不再是瓶颈，剩下的瓶颈就是它。

## 决定：把"交出去"和"等回执"拆成两件事

新增契约 `InFlightDispatch`（`awaitDelivery()`）与 `OutboxDispatcher.beginDispatch(message)`。
relay 先把**整批**交给传输，再逐个等；确认下来的行**一条语句**记账
（`OutboxStore.markSent` 由单 id 改为 id 列表）。

`beginDispatch` 的**默认实现就是同步的 `dispatch`**，返回 `InFlightDispatch.CONFIRMED`。
所以 `LoggingOutboxDispatcher`、`InProcessOutboxDispatcher`、以及任何消费方自定义传输都**一行不用改**、
行为完全不变；只有"send 本来就是异步的"传输才有东西可重叠。

### 为什么这不动任何保证

报告里写的做法是「按 subject 有序发出、按序等 future、**首个失败 fail-fast**」。落地时发现前提已经变了：
第 7 项把 claim 收紧成**只有聚合队头可领**之后，**一批 claim 出来的行必然两两不同 subject**
（无 subject 的行不参与排序，本就无序可乱）。于是——

- **批内根本不存在需要保序的两条消息**，"按序等 + fail-fast"是多余约束；
- 更进一步，fail-fast 反而**有害**：它会丢下已经交给 broker 的后续 send 不等，那些行只能被重投，
  白白制造重复。
- 跨轮也安全：某聚合的下一条要等这一条 `markSent` 之后才可领，而 `markSent` 在 ack 之后。
  所以一个聚合的两条事件**永远不会同时在飞**——这条现在有测试盯着
  （`twoEventsOfOneAggregateAreNeverInFlightTogether`，时间线断言 `sent e1, acked e1, sent e2, acked e2`）。

其余不变量逐条对照：一行只有**自己的**投递被确认后才记 sent；一行的失败仍只算它自己的（重试/死信照旧）；
目的地不可达的行仍在交出去**之前**就被拦下。

## 超时的度量起点改了：从"开始等"改成"交出去那一刻"

这是本项里唯一一处**必须**改的算术。若每次等待各自起算 `sendTimeout`，broker 哑了时 N 条会串成 N × timeout。
把 deadline 在 `beginDispatch` 里就钉死，N 条几乎同时到期——**整批停摆的代价是一个 timeout，不是一批**。

正因如此，第 7 项立下的租约算式**原样成立**：poll 预算（半个租约）只需为**一次**投递留余量。
`producer.send-timeout-ms < lease/2` 这条启动守卫一个字不用改。

## 追踪接缝：span 必须"离开当前线程"但不结束

原先 `tracer.restore(...)` 的 scope 包住整个同步 dispatch。流水线化之后，producer 埋点读 ambient context
只发生在**交出去**那一刻，而 span 要等 ack 才能定成败——两者不再重合。

三条路都不行：交出去就结束 span → 失败的投递在链路里显示成功（撒谎）；把 N 个 scope 同时开着 →
关闭顺序是 FIFO 而非 LIFO，OTel 会报上下文错乱；失败时另开一个 span 记错 → 一条消息两个 span。

所以给 `StoreAndForwardTracer.Scope` 加了 `detach()`：**停止充当当前上下文，但不结束 span**。
default 空实现，NoOp 与 process-manager 侧零改动；OTel 实现里 `detach()` 关 otel scope、`close()` 再 `end()` span，
幂等。这本就是 OTel 自己分开的两件事（Span 与 Scope），我们的 SPI 之前把它们捏在了一起。

## 顺手修掉的毒消息死锁：DLT 固定源分区号

`DeadLetterPublishingRecoverer` 原先解析成 `new TopicPartition(record.topic() + ".DLT", record.partition())`。
DLT 通常按"涓流"建，分区数少于它影子的主题；点名一个那里不存在的分区 → 发布失败 → recoverer 失败 →
`DefaultErrorHandler` 重新 seek 重试整轮 → **毒消息永远出不去、分区无限停滞**，与 DLT 的目的正相反。

**比报告说的窄一些，但也更值得修**：Spring Kafka 自带 `verifyPartition=true`，会先问 broker 该分区在不在，
不在就改成 -1——常见情况它救得回来。但它救不了**元数据拿不到**的情况（`partitionsFor` 返回 null 或超时），
而那恰恰就是"DLT 主题压根不存在"的时候。且这个保险每条死信都要付一次**阻塞式**元数据查询（默认 5s 上限）。

改成显式不指定分区（-1）后：`checkPartition` 直接短路、那次阻塞查询没了、DLT 分区数多少都对。
而"同一聚合的死信落同一分区"照旧成立——recoverer 会把源记录的 **key** 抄到 DLT 记录上，
默认分区器按 key 散列。**共位一直是 key 的功劳，不是分区号的**。

## 落地

- 新增 `outbox/InFlightDispatch.java`；`OutboxDispatcher` 加默认方法 `beginDispatch`。
- `StoreAndForwardTracer.Scope` 加默认方法 `detach()`；OTel 实现拆开 scope 与 span 的生命周期。
- `OutboxStore.markSent(List<String>, Instant)`；jdbc 走 `IN (...)`，MyBatis 走 wrapper 的 `.in(...)`。
- `OutboxRelay`：`dispatchAll` 拆成 `handOver` → `confirmAll` → `recordDelivered` 三段。
  预算耗尽只停**交付**，已交出去的一律等完（丢下它们要么白锁一个租约，要么凭空造重复）。
- `OutboxObserver.markSentFailed()` → `markSentFailed(int rows)`：一次批量记账失败预示 N 条重复，
  按条计数才读得出代价。
- `KafkaOutboxDispatcher.beginDispatch(message, topic)`，deadline 在交出去时钉死；`dispatch` 成为它的同步外壳。
- `RoutingOutboxDispatcher.beginDispatch`：本地腿同步（`CONFIRMED`），Kafka 腿透传真正的待确认句柄。
- `deadLetterDestination(record)` 提成包私有静态方法，可单测。
- **无新配置项**；`producer.send-timeout-ms` 与 `relay.lease-duration` 的关系不变。

## 验证

`OutboxRelayPipelineTest`（jdbc 5 例）：整批先交出去再等（时间线断言）；一个聚合的两条事件永不同飞；
批内一条失败仍只算它自己（兄弟行照样 sent、失败行 attempts=1）；确认下来的一批**只写一次**；
批量记账失败则每一行都退回重投（不计 attempts、不留租约、观察者按行计 2）。

`KafkaOutboxDispatcherTest` 增 2 例：三条记录在任何一条被等待之前就已全部交给 producer；
五条停摆的 send 在 ~400ms 一起到期而非串成 2s。

`KafkaErrorHandlerTest` 增 1 例：死信目的地是 `<topic>.DLT` 且**不点名分区**。

**负向对照**：把 `handOver` 改回交出去就立刻 `awaitDelivery`，时间线断言按预期失败
（`[sent e1, acked e1, sent e2, ...]`）。

库 48 模块全门禁 + 脚手架 `multi-module` 两个 reactor `mvn clean verify` 全绿。

## 关联

- 父：[[report-00003-ddd-library-review-2026-07-29]]（§2 Outbox「吞吐上限约 100 msg/s」与「DLT 固定源分区号」两条、§3 第 10 项）
- **前提**：批内不重复 subject 这条性质来自 [[issue-00108-a-killed-relay-instance-stops-all-delivery]]
  的队头 claim——没有它，流水线化就得真的按 subject 排序并 fail-fast
- 度量这次改动的接缝来自 [[issue-00110-the-outbox-had-no-metrics-at-all]]（`dispatch.latency` / `claim.latency`）
- 目的地在交出去之前就拦下：[[issue-00109-a-vanished-route-turned-an-externalized-event-local]]
- 追踪接缝：[[design-00005-observability-and-distributed-tracing]]
