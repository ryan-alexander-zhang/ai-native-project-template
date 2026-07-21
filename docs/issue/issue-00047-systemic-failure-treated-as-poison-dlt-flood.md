---
id: issue-00047-systemic-failure-treated-as-poison-dlt-flood
type: issue
role: main
status: open
parent: plan-00006-middleware-integration
---

# 消费者把系统性/基础设施故障当毒丸:DB 短暂不可用即批量误进 DLT,并破坏同 aggregate 顺序

## 问题(现状,file:line 为证)

- **等级:High(可靠性 + 静默破坏因果顺序)**。
- 消费桥的错误分类只把三类判为**永久**(不重试、直投 DLT):`AipersimmonDddMessagingKafkaAutoConfiguration.buildErrorHandler`
  的 `addNotRetryableExceptions(UnknownIntegrationEventException, MalformedIntegrationEventException, JsonProcessingException)`
  (`AipersimmonDddMessagingKafkaAutoConfiguration.java:232`)。**其余一切异常**走"重试 N 次后 → recoverer → `<topic>.DLT`"。
- 重试预算很小:`KafkaMessagingProperties.Consumer.Retry` 默认 `maxRetries=3`、`1000/×2.0/10000`
  (`KafkaMessagingProperties.java:117-123`)≈ **7 秒**退避窗口。
- 而 `onMessage` 是 `@Transactional`,inbox 写(`JdbcInbox.java:28-30`,`SELECT COUNT` + `INSERT aipersimmon_inbox`)
  在该事务内。**DataSource 不可用 / 连接池耗尽 / 下游整体故障**时,inbox 写或业务 handler 抛的是
  `DataAccessException` 之类——**不在**永久清单里 → 按"瞬时"重试 3 次(~7s)→ **进 DLT**。

于是当 PostgreSQL 宕机 ~30s:

```text
inbox INSERT 失败 → 1/2/4s 重试 → 仍失败 → 进 DLT → offset 前进 → 下一条重复 → 大量健康消息涌入 DLT
```

DB 宕机是**系统性故障,不是消息本身有问题**,健康消息被误判为毒丸。

## 根因(第一性)

1. **观察 vs 期望**:期望"毒丸(契约/数据错误)才 DLT;基础设施故障应等它恢复";实际"除三类永久错误外,一切失败
   在 ~7s 后一律 DLT",把**瞬时/系统性**与**永久/数据**混为一谈。
2. **最小机制**:`DefaultErrorHandler` 的 not-retryable 白名单只覆盖了"消息本身坏"的三类;"环境坏"这一大类落进默认
   的"重试几次就 DLT"。
3. **更严重的次生后果——破坏顺序**:事件按 `subject`(如 `orderId`)分区保序,但 DLT 让 offset 前进:

   ```text
   Order A / Event 1 → DB 故障 → 进 DLT(被跳过)
   Order A / Event 2 → DB 恢复 → 正常执行
   ```

   Kafka 分区顺序没变,但业务上 Event 1 被跳过,**同一 aggregate 的因果顺序被静默破坏**;依赖订单因果链的 process
   manager 会据此进入错误状态。

## 复现(test-first)

- 装配消费桥 + 一个会话中途"不可用"的 DataSource(或让 inbox/handler 抛 `DataAccessException` 若干秒),投递 N 条健康
  事件;断言:现状下它们进了 `<topic>.DLT`;修复后它们**不进 DLT**,而是待 DataSource 恢复后被处理,顺序不乱。
- 同 aggregate 两条事件,第一条遇"故障窗口",断言修复后第二条**不会先于**第一条被处理。

## 修复/建议

把"消息坏(毒丸)"与"环境坏(系统性)"分开处理:

- **契约/数据永久错误**(未知类型、缺 header、反序列化失败):维持立即 DLT。
- **瞬时并发**(偶发死锁、短超时):少量同步重试(现状即可)。
- **系统性/基础设施不可用**(`DataAccessException`、`CannotGetJdbcConnectionException`、连接池耗尽、下游整体故障):
  **不要 DLT**。用 `ContainerPausingBackOffHandler` 在长退避时**暂停 container**(仍继续必要的 poll,避免超过
  `max.poll.interval.ms` 触发 rebalance),等恢复后从原 offset 继续;或对这类异常无限退避、永不移交 recoverer。可暴露
  consumer lag 供告警(circuit-breaker 语义)。
- 让分类**可配置/可扩展**(哪些异常算 systemic),而不是写死。

这样 DB 短暂不可用不再制造 DLT 洪水,也不再破坏同 aggregate 顺序。

## 关联

- [[plan-00006-middleware-integration]]
- [[decision-00014-cloudevents-integration-event-contract]](§5 未知 (type,version) → DLT 的边界:仅适用于"消息坏",不含"环境坏")
- [[issue-00030-single-topic-fanout-all-consumers-see-all-events]](同一消费桥的阻塞/隔离面)
