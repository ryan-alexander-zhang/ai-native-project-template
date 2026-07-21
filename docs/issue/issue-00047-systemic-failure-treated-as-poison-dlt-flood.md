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

## 修复方案(已定)

**核心:把失败分成三层,显式分类、分别路由;"环境坏"这一层永不 DLT。** 所有涉及的 Spring Kafka API 已核实存在于
spring-kafka 3.3.x。

| 层 | 判定 | 行为 |
|---|---|---|
| **毒丸**(消息坏,确定性) | `UnknownIntegrationEventException` / `MalformedIntegrationEventException` / `JsonProcessingException` + 可配置追加 | **立即 DLT**(不变) |
| **系统性**(环境坏) | `DataAccessException` 家族(连接失败、连接池耗尽…)+ 可配置追加(下游客户端异常) | **无限退避重试、暂停分区,永不 DLT**;恢复后原 offset 继续 |
| **其余**(不确定) | 未分类异常 | **有界退避 N 次 → DLT**(现状;安全网,防止未预见的"总失败"记录永久堵塞分区) |

### 机制

- 保留 `DefaultErrorHandler(recoverer = DLT, 默认 BackOff = 有界)`。
- 毒丸层:`handler.addNotRetryableExceptions(...)`(现有三类 + 可配置)→ 立即移交 recoverer(DLT)。
- 系统性层:`handler.setBackOffFunction((record, ex) -> isSystemic(ex) ? new FixedBackOff(interval, FixedBackOff.UNLIMITED_ATTEMPTS) : null)`
  —— 系统性异常拿到**无限次**退避,永远到不了 recoverer(即永不 DLT);返回 `null` 则用默认有界退避(其余层)。
- 暂停而非阻塞线程:用 `DefaultErrorHandler(recoverer, backOff, new ContainerPausingBackOffHandler(pauseService))`。长/无限
  退避时它**暂停分区并继续 poll**,不 `Thread.sleep` 占线程、也不因久等触发 `max.poll.interval.ms` rebalance。
- `isSystemic(ex)`:沿 **cause 链**匹配(与现有 `JsonProcessingException` 分类同法),默认命中 `DataAccessException`;可配置补充。

### 为什么这样同时解决 DLT 洪水 + 顺序破坏

系统性失败下 **offset 不前进、不 DLT**,分区停在失败记录上自旋直到环境恢复 → 同 `subject`(aggregate)的因果顺序**天然
保留**;DB 恢复后从原位继续,健康消息不再涌入 DLT。毒丸仍 DLT(它确定性地无法处理)。

### 配置面(`KafkaMessagingProperties.Consumer`)

- `retry`(现有):仅用于"其余"层的有界退避。
- `systemic-backoff-interval-ms`(默认如 10000):系统性层的暂停/重试间隔。
- 可选 `systemic-exceptions` / `poison-exceptions`:向两层各自追加异常类型(默认分别是 `DataAccessException` / 现有三类)。

### 可观测性

进入系统性暂停时 WARN 日志 + 暴露 consumer lag / 暂停状态(供告警),避免"静默卡住"被误当正常。

### 边界与留待决策

- **毒丸也会破坏顺序**(OrderA/Event1 malformed → DLT → Event2 照常):这与系统性是两码事,本 issue 不处理。严格保序流
  可选"任何 DLT 都停该分区、人工介入"而非跳过——单列为后续决策。
- 无限重试**只在系统性层**;"其余"层仍有界 → DLT,确保未预见的确定性失败不会永久堵塞分区(不是把所有失败都改成永不 DLT)。

### 测试(embedded broker)

- 系统性:让 inbox/handler 在一个窗口内抛 `DataAccessException` → 断言记录**不进 DLT**、容器暂停;"DB"恢复后记录被处理,
  同 aggregate 两事件顺序不乱。
- 毒丸:未知类型仍进 DLT(现有 `KafkaDeadLetterIntegrationTest` 不回归)。
- 其余:一个持续抛普通异常的记录,有界重试后**仍进 DLT**(安全网不被破坏)。

## 关联

- [[plan-00006-middleware-integration]]
- [[decision-00014-cloudevents-integration-event-contract]](§5 未知 (type,version) → DLT 的边界:仅适用于"消息坏",不含"环境坏")
- [[issue-00030-single-topic-fanout-all-consumers-see-all-events]](同一消费桥的阻塞/隔离面)
