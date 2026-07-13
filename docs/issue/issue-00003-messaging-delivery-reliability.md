---
id: issue-00003-messaging-delivery-reliability
type: issue
role: main
status: open
parent: design-00001-aipersimmon-ddd-and-scaffold
---

# 消息投递可靠性:重试上限 + 退避 + 死信(DLQ)

异步集成事件投递(outbox 发件 / kafka 收件)在**失败**时缺少受控处理:没有瞬时/永久分类、没有
重试上限与退避、没有死信旁路。这是**投递可靠性**问题,与 [[design-00003-exception-model]] 的
"错误建模 / HTTP 契约" 正交——最初误并入异常体系(analysis-00010 曾列为缺口 #6),现拆出独立追踪。

## 问题(现状,file:line 为证)

两条链路,两种都不对的失败模式:

- **生产侧 `OutboxRelay.relay()`**(`aipersimmon-ddd-outbox-jdbc/.../OutboxRelay.java:48-57`,mybatis 版同构):
  失败仅 `attempts++` + `warn`,行仍 `sent=FALSE`;配合 `WHERE sent=FALSE ORDER BY created_at ASC` 每 1s 轮询
  → **毒丸行无限重试**。无 `max-attempts`、无退避(立即下轮再撞)、无死信;`attempts` 只是无人读的计数器;
  毒丸永占队头 → 日志刷屏 + DB/网络空转,重则**队头阻塞**。
- **消费侧 `KafkaIntegrationEventListener.onMessage()`**(`aipersimmon-ddd-messaging-kafka`,`@Transactional`):
  handler 抛异常 → inbox 记录回滚 → 冒泡到容器;而 autoconfig **未配置** `DefaultErrorHandler`/
  `DeadLetterPublishingRecoverer`/`BackOff` → 落到 Spring Kafka 默认(有限次重试后**提交位点跳过**),
  且**无 DLT 捕获** → 毒丸记录最终**静默丢失**(无留存、无取证、无重放)。
- 旁证:`JdbcDeadlineScheduler.poll()`(saga-spring)同为 catch→warn→无限重试。

一句话:生产侧"永不放弃"(死循环),消费侧"悄悄放弃"(静默丢失)。

## 提议(受控可靠性 = 三件事)

1. **瞬时 vs 永久 分类**:`FailureClassifier` SPI。transient(网络/超时/死锁/`TransientDataAccessException`/5xx)
   → 重试;permanent(反序列化失败/`ClassNotFoundException`/约束违反/4xx/业务永久拒绝)→ 立刻死信。
2. **有界重试 + 退避**:`max-attempts`(默认 8)+ 指数退避 + jitter。outbox 侧落成 `next_attempt_at` 列
   (`SELECT ... AND next_attempt_at <= now`),不再每 1s 硬撞;达上限转 permanent。
3. **死信(放弃 ≠ 丢失)**:
   - `-outbox` 增 `DeadLetterStore` SPI;`-outbox-jdbc`/`-mybatis-plus` 落 `aipersimmon_dead_letter` 表
     (原消息 + last_error + attempts + failed_at + reason),relay 超限/permanent 时**同事务** move。
   - `-messaging-kafka` 装配 `DefaultErrorHandler(ExponentialBackOffWithMaxRetries)` +
     `DeadLetterPublishingRecoverer` → `<topic>.DLT`。
   - 进死信发 `error` 日志 + traceId,接告警;可人工/工具重放。

## 依据(大厂/标准)

DLQ 是消息中间件标配:AWS SQS(redrive `maxReceiveCount`)、RabbitMQ(dead-letter exchange)、
Azure Service Bus(`MaxDeliveryCount`)、GCP Pub/Sub(`dead_letter_topic`)。Spring 官方模式即
`DefaultErrorHandler` + `DeadLetterPublishingRecoverer` + `ExponentialBackOffWithMaxRetries`。
退避+jitter 见 AWS《Exponential Backoff And Jitter》;瞬时/永久见 gRPC `UNAVAILABLE` vs `INVALID_ARGUMENT`
与 Spring `TransientDataAccessException`;EIP 的 Dead Letter Channel / Invalid Message Channel。

## 边界

- 与 **inbox 幂等正交**:inbox 解决"至少一次导致的重复消费"(去重);DLQ 解决"永远失败的消息"(放弃+留存)。
  二者都要、不互替(承 [[decision-00007-web-api-response-envelope]] §五三层分离,延伸到消费侧)。
- 与 **HTTP 异常契约**([[design-00003-exception-model]])无关,不影响其验收。
- 若实现规模变大,可从本 issue 抽出独立 `design` 承载 SPI/表结构/装配细节。

## 影响模块

`-outbox`(SPI + in-process 默认)、`-outbox-jdbc` / `-outbox-mybatis-plus`(`dead_letter` 表 + relay 接入)、
`-messaging-kafka`(错误处理器 + DLT 装配)。配置:`max-attempts`、backoff、dead-letter 开关、DLT 命名。

## 验收标准(GWT)

- **AC-1**:永久失败(如反序列化失败)的 outbox 消息,不重试即进 `dead_letter`,不再 `sent=FALSE` 空转。
- **AC-2**:瞬时失败的 outbox 消息按退避重试,达 `max-attempts` 后进 `dead_letter`;正常消息不受影响。
- **AC-3**:Kafka 毒丸记录经退避重试后进 `<topic>.DLT`(而非静默丢弃);inbox 幂等语义不回归。
- **AC-4**:死信可被工具/人工重放回主流程。
- **AC-5**:jdbc 与 mybatis-plus 两后端行为一致;以 Testcontainers(Postgres + Kafka)验证 AC-1..4。

## 关联

- [[design-00001-aipersimmon-ddd-and-scaffold]] —— outbox/inbox/messaging 模块的所属设计。
- [[analysis-00010-exception-model]] —— 曾误列为缺口 #6,现移出、指向本 issue。
- [[decision-00007-web-api-response-envelope]] §五 —— 防重放/幂等/去重"三层分离"思想。
