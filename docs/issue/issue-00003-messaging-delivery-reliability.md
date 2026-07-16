---
id: issue-00003-messaging-delivery-reliability
type: issue
role: main
status: resolved
parent: design-00001-aipersimmon-ddd-and-scaffold
---

# 消息投递可靠性:重试上限 + 退避 + 死信(DLQ)

异步集成事件投递(outbox 发件 / kafka 收件)在**失败**时缺少受控处理:没有瞬时/永久分类、没有
重试上限与退避、没有死信旁路。这是**投递可靠性**问题,与 [[design-00003-exception-model]] 的
"错误建模 / HTTP 契约" 正交——最初误并入异常体系(analysis-00010 曾列为缺口 #6),现拆出独立追踪。

## 问题(现状,file:line 为证)

两条链路,两种都不对的失败模式:

- **生产侧 `OutboxRelay.relay()`**(jdbc / mybatis-plus 同构):失败仅 `attempts++` + `warn`,行仍
  `sent=FALSE`;`SELECT ... WHERE sent=FALSE AND attempts < max-attempts` 每 1s 轮询。达 `max-attempts`
  (默认 10)后该行被 `attempts < ?` **永久排除**,却仍留在表内 `sent=FALSE` → **有界但不可恢复的遗弃**
  (非早先描述的"无限重试":`max-attempts` 已由提交 `4a0e94b` 加入)。其代价:(1)**零退避**——上限内每 1s 硬撞
  (default 10 次 ≈ 10s),broker 重启即熬过,瞬时抖动坐实为永久跨 BC 不一致;(2)**无死信**——遗弃行是隐形墓碑,
  无 `last_error`/`dead_at`/重放入口;(3)**无瞬时/永久分类**——反序列化等必然失败也白耗满 10 次;(4)遗弃行被排除
  后其 `subject` 后续事件继续发送 → **give-up 点之后静默乱序**。
- **消费侧 `KafkaIntegrationEventListener.onMessage()`**(`aipersimmon-ddd-messaging-kafka`,`@Transactional`):
  handler 抛异常 → inbox 记录回滚 → 冒泡到容器;而 autoconfig **未配置** `DefaultErrorHandler`/
  `DeadLetterPublishingRecoverer`/`BackOff` → 落到 Spring Kafka 默认(零退避重试 9 次后**提交位点跳过**),
  且**无 DLT 捕获** → 毒丸记录最终**静默丢失**(无留存、无取证、无重放)。永久失败(反序列化、未知
  `(type, version)` 抛 `UnknownIntegrationEventException`)同样白耗重试后被丢。
- 旁证:`JdbcDeadlineScheduler.poll()`(saga-spring)同为 catch→warn→无限重试。

一句话:生产侧"永不真正放弃却静默丢行"(有界遗弃),消费侧"悄悄放弃"(静默丢失)。

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
- **AC-5**:jdbc 与 mybatis-plus 两后端行为一致(以 **H2** 验证);Kafka 的 poison → DLT → offset 前进 → inbox 链路
  以 **Embedded Kafka**(in-JVM,无 Docker)验证。**不含** PostgreSQL / Testcontainers 方言级端到端验证——刻意取舍,
  避免 Docker 依赖与 CI 变重(见 [[issue-00010-verify-kafka-dlt-with-embedded-broker]])。

## 落地进度

两侧均已落地(拆两次提交:先 outbox / H1,后 Kafka / H3);库反应堆全绿,multi-module 脚手架全绿。

**生产侧(outbox,对应 H1)**:

- **`FailureClassifier` SPI + `DefaultFailureClassifier`**(`-outbox`,framework-free):瞬时(默认)/ 永久
  (`UnknownIntegrationEventException`、Jackson `JsonProcessingException`,沿 cause 链识别)分类;可覆盖 bean。
- **`RetryBackoff`**(`-outbox`):等-jitter 指数退避(cap 逐次翻倍、封顶、`[cap/2, cap]`),经 `next_attempt_at`
  列落库,`SELECT ... AND (next_attempt_at IS NULL OR next_attempt_at <= now)` 到期才取;`base=0` 可关退避。
  配置 `aipersimmon.ddd.outbox.retry.base-backoff-ms`(默认 1000)/ `max-backoff-ms`(默认 60000)。
- **`DeadLetterStore` SPI**(`-outbox`)+ `aipersimmon_dead_letter` 表(jdbc / mybatis-plus):永久失败或耗尽重试的
  行经 `TransactionTemplate` **同事务从 outbox 移入**死信表(不再滞留热表),留存 `attempts`/`reason`
  (`PERMANENT` | `RETRIES_EXHAUSTED`)/ `last_error`/ `failed_at`;`replay(eventId)` 反向搬回 outbox 重投。
- **relay 收口**:失败先分类——永久即刻死信(不耗重试),瞬时退避重试至 `max-attempts`(默认仍 10)再死信;
  live 重试行仍按 `subject` 保序回避,死信行放行其聚合后续。
- 验证:`RetryBackoff` 单测;jdbc `OutboxRelayResilienceTest`(保序 / 耗尽即死信 / 永久即死信 / replay 回投)+
  `OutboxRelayBackoffTest`(退避入未来、下轮跳过);mybatis-plus 对等弹性测试(双后端一致)。
- 满足 **AC-1 / AC-2 / AC-4**,及 **AC-5** 的 jdbc / mybatis-plus 一致性(以 H2 验证,未引入 Testcontainers)。

**消费侧(Kafka DLT,对应 H3)**:

- `-messaging-kafka` autoconfig 在消费者启用且有 `KafkaTemplate` 时装配
  `DefaultErrorHandler(ExponentialBackOffWithMaxRetries)` + `DeadLetterPublishingRecoverer` → `<topic>.DLT`;
  Spring Boot 的容器工厂 configurer 自动把这唯一的 `CommonErrorHandler` bean 应用到监听容器。取代 Spring Kafka
  默认的"零退避重试 9 次后静默跳过"。
- **永久失败即刻 DLT**:`UnknownIntegrationEventException`、Jackson `JsonProcessingException` 标为 not-retryable
  (沿 cause 链识别),不耗退避直接死信——与 outbox 侧 `DefaultFailureClassifier` 的永久集一致,两传输对"何为不可
  重试"口径统一;亦落实集成事件契约"未知入站 `(type, version)` → DLT、无 FQCN 回退"这一边界。
- 配置:`aipersimmon.ddd.messaging.kafka.consumer.retry.{max-retries=3, initial-interval-ms=1000, multiplier=2.0,
  max-interval-ms=10000}`。
- 验证:`KafkaErrorHandlerTest`(永久 → 首次即 recover / 瞬时 → 首次不 recover,不接触 broker)+
  `AutoConfigurationWiringTest`(启用即装配 `DefaultErrorHandler`、禁用则无、可覆盖)。
- **Embedded Kafka 端到端**(`KafkaDeadLetterIntegrationTest`,in-JVM broker,无 Docker):poison(未知 `(type, version)`)
  → `<topic>.DLT`,消费者越过毒丸继续消费后续正常消息(offset 前进),毒丸的 inbox 标记随事务回滚、正常消息标记
  提交。此测试当场揪出一个 H3 潜伏缺陷:`DeadLetterPublishingRecoverer` 默认目的地并非文档承诺的 `<topic>.DLT`,
  已改为**显式目的地解析器**(`<topic>.DLT`,按源分区键),使实际行为与文档一致——单元/mock 测试发现不了这类问题,
  正是补真实链路测试的价值(见 [[issue-00010-verify-kafka-dlt-with-embedded-broker]])。

全部 AC(1–5)达成,本 issue `resolved`。

## 关联

- [[design-00001-aipersimmon-ddd-and-scaffold]] —— outbox/inbox/messaging 模块的所属设计。
- [[analysis-00010-exception-model]] —— 曾误列为缺口 #6,现移出、指向本 issue。
- [[decision-00007-web-api-response-envelope]] §五 —— 防重放/幂等/去重"三层分离"思想。
