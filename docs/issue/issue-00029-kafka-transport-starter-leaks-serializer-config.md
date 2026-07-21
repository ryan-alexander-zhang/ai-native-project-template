---
id: issue-00029-kafka-transport-starter-leaks-serializer-config
type: issue
role: main
status: open
parent: plan-00006-middleware-integration
---

# messaging-kafka 拥有线格式,却把 Kafka 序列化器配置外推给使用者

## 问题(现状,file:line 为证)

- **等级:Medium(易用性泄漏 + 一个 footgun)**。
- `messaging-kafka` 完全拥有线格式:`KafkaOutboxDispatcher.java:42` 持有 `KafkaTemplate<String, String>`,值是
  **已序列化的 JSON 字符串**,元数据全走 `ce_*` 头(CloudEvents binary binding,`decision-00014`)。
- 但它只**注入** `KafkaTemplate<String,String>`(`AipersimmonDddMessagingKafkaAutoConfiguration.java:55`
  `kafkaOutboxDispatcher(KafkaTemplate<String,String> ...)`),**不注册自己的 `KafkaTemplate`/`ProducerFactory`/
  序列化器**,依赖 Boot 的 `spring.kafka.*`。于是使用者(以及官方样例 `integration-events-over-kafka` 的
  `application.properties`)必须手配 `spring.kafka.producer/consumer.{key,value}-(de)serializer=...String...`。
  本次集成的 `multi-module/start/application.yml` 也照抄了这几行。

## 根因(第一性)

1. **观察 vs 期望**:期望"传输 starter 决定线格式 → 它也应提供匹配的序列化器默认";实际"线格式由 starter 决定,
   序列化器却要使用者手配"。
2. **最小机制**:starter 复用 Boot 的 `KafkaTemplate`(`@AutoConfiguration(after = KafkaAutoConfiguration)`),而 Boot
   不提供序列化器默认 → 责任落到应用。
3. **真根因**:抽象泄漏——组件掌握了契约(String value + `ce_` 头)却不封装契约所**唯一决定**的那部分配置。
4. **附带 footgun**:若使用者按常见习惯把 value-serializer 设成 `JsonSerializer`(很多 Kafka 应用的默认),
   dispatcher 传入的**已是 JSON 字符串**会被**二次 JSON 编码**(加引号/转义),消费桥按原始 JSON 解析即失败。
   即"配错比不配更隐蔽"。

## 复现(test-first)

- 切片测试:装配 `messaging-kafka` + Boot Kafka,但**不设** `spring.kafka.*-serializer` → `ProducerFactory` 缺序列化器,
  发送失败。修复后(starter 自带 String 默认)同样配置应能发送。
- 另一个用例:把 value-serializer 设为 `JsonSerializer`,断言消费端解析失败(证明二次编码 footgun),修复后 starter
  用专属 `KafkaTemplate` 隔离,应用的 JSON 设置不再污染 aipersimmon 通道。

## 修复

starter 提供**专属**的 `KafkaTemplate<String,String>`(或 `ProducerFactory`/`ConsumerFactory`)bean,序列化器固定为
String,`@ConditionalOnMissingBean`,并让 `KafkaOutboxDispatcher`/`KafkaIntegrationEventListener` **按名注入该专属
bean**(而非全局 `KafkaTemplate`)。这样:aipersimmon 通道的线格式自洽、开箱即用;且与应用自身其它 Kafka 用途
(可能用别的序列化器)互不干扰。使用者不再需要为 aipersimmon 手配序列化器。

## 关联

- [[plan-00006-middleware-integration]]
- [[decision-00014-cloudevents-integration-event-contract]](线格式:String value + `ce_` 头)
