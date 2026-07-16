---
id: issue-00010-verify-kafka-dlt-with-embedded-broker
type: issue
role: patch
status: resolved
parent: issue-00003-messaging-delivery-reliability
---

# H3 代码成立,但 AC-5 验收结论言过其实(且暴露 DLT 目的地潜伏缺陷)

[[issue-00003-messaging-delivery-reliability]] 的 Kafka 错误处理/退避/DLT recoverer 装配正确,但其 **AC-5** 明确要求
"以 Testcontainers(Postgres + Kafka)验证",而 H3 实际只用 H2 + mock recoverer(不接触 broker),却在落地进度里声称
"AC-1..AC-5 达成"。这是 over-claim:没有真实 broker 端到端,就不能宣称 AC-3/AC-5 已验证。

## 问题(现状)

- H3 的 Kafka 测试(`KafkaErrorHandlerTest` / `AutoConfigurationWiringTest`)是**无 broker** 的单元/装配级验证:证明
  错误处理器分类正确、bean 装配且可覆盖,但**未**证明真实链路 poison → `<topic>.DLT` → offset 前进 → inbox。
- AC-5 原文要求 Testcontainers(Postgres + Kafka);实际未做,却标 `resolved` 并称 AC 全达成。

## 决策

- **不引入 Testcontainers / PostgreSQL + Kafka**:避免 Docker 依赖与 CI 变重(与团队既有取向一致)。
- **补一条无 Docker 的 Embedded Kafka 真实链路测试**,并把 AC-5 改写为**实际验证边界**:jdbc/mybatis-plus 一致性用
  H2;Kafka 的 DLT/offset/inbox 用 Embedded Kafka;显式声明不含 PostgreSQL/Testcontainers 方言级验证。

## 修复

1. **`KafkaDeadLetterIntegrationTest`**(库模块 `-messaging-kafka`,`@EmbeddedKafka`,in-JVM broker):
   - 发送 poison(合法头但未知 `(type, version)`)与其后同分区的正常消息;
   - 断言 poison 进入 `<topic>.DLT`;
   - 断言消费者**越过**毒丸并处理了后续正常消息(offset 前进);
   - 断言毒丸的 inbox 标记随其失败事务**回滚**、正常消息标记**提交**(用真实 JDBC inbox + H2)。
   - 放在库模块(而非 `integration-events-over-kafka` 样例):样例仅 demo、且已因 `@EventType` 必填等变更**过期失效**
     (`ReservationPlaced` 缺 `@EventType`、schema 缺 `source`/`subject`/因果/`next_attempt_at` 列),不宜承载权威验收。
2. **修掉测试当场揪出的 H3 潜伏缺陷**:`DeadLetterPublishingRecoverer` 的默认目的地并非文档承诺的 `<topic>.DLT`,
   改为**显式目的地解析器** `new TopicPartition(record.topic() + ".DLT", record.partition())`,按源分区键落 DLT,使
   实际 topic 名与 Javadoc / ADR / issue 一致。单元/mock 测试发现不了这类问题——正是真实链路测试的价值。
3. **改写 `issue-00003` AC-5** 与落地进度,如实反映验证边界与本次 DLT 修复。

## 验证结果

- `KafkaDeadLetterIntegrationTest` 全绿(embedded broker,~2.5s);既有 20 个无-broker 测试不受影响。库反应堆全绿;
  multi-module 不涉及 Kafka。
- 测试依赖仅新增 test scope:`spring-kafka-test`、`aipersimmon-ddd-inbox-jdbc`、`spring-boot-starter-jdbc`、`h2`。

## 关联

- [[issue-00003-messaging-delivery-reliability]] —— H3 的所属 issue;AC-5 在此如实收紧,DLT 目的地在此修正。
- [[issue-00008-strict-inbound-cloudevents-validation]] —— poison 的一种(未知类型)即永久失败 → DLT,同一 not-retryable 装配。
