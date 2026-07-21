---
id: issue-00029-kafka-transport-starter-leaks-global-transport-infrastructure
type: issue
role: main
status: open
parent: plan-00006-middleware-integration
---

# messaging-kafka 拥有线格式/消费语义,却复用应用全局的 Kafka 基础设施(序列化器 + 错误处理器 + 事务管理器)

## 问题(现状,file:line 为证)

- **等级:Medium(抽象泄漏 + 三个 footgun,其中两个会污染使用方自己的 Kafka 用途)**。
- 同一根因(starter **拥有**契约/语义,却**不隔离**自己的传输基础设施,而是搭 Boot 的全局默认)派生三个泄漏:

**(a) 序列化器泄漏 + 二次编码 footgun。** `messaging-kafka` 完全拥有线格式:值是**已序列化的 JSON 字符串**,
元数据全走 `ce_*` 头(CloudEvents binary binding,`decision-00014`)。但它只**注入**全局
`KafkaTemplate<String,String>`(`AipersimmonDddMessagingKafkaAutoConfiguration.java:158`
`routingOutboxDispatcher(KafkaTemplate<String,String> ...)`),**不注册**自己的 `KafkaTemplate`/`ProducerFactory`/
序列化器;消费侧同样**不注册** `ConsumerFactory`,依赖 Boot 的 `spring.kafka.*`。于是使用者(以及官方样例
`integration-events-over-kafka`、本次的 `multi-module/start/application.yml`)必须手配
`spring.kafka.producer/consumer.{key,value}-(de)serializer=...String...`。**footgun**:若使用者按常见习惯把
value-serializer 设成 `JsonSerializer`,dispatcher 传入的**已是 JSON 字符串**会被**二次 JSON 编码**(加引号/转义),
消费桥按原始 JSON 解析即失败——"配错比不配更隐蔽"。

**(b) 全局 `CommonErrorHandler` 污染其他 listener。** starter 注册
`AipersimmonDddMessagingKafkaAutoConfiguration.java:189-194`
`@ConditionalOnMissingBean(CommonErrorHandler.class) DefaultErrorHandler kafkaErrorHandler(...)`,其 Javadoc(:185-187)
自陈:"Boot's container-factory configurer applies this single `CommonErrorHandler` bean to the listener container
**automatically**"。即这**唯一**的 `CommonErrorHandler` bean 会被 Boot 应用到**所有**使用默认 container factory 的
listener——含使用方自己业务的 Kafka 消费者。aipersimmon 的重试/退避/DLT 策略(含把
`UnknownIntegrationEventException` 等标记为不可重试直接 dead-letter)于是被**强加**到与集成事件无关的业务 listener 上。

**(c) Listener 用未限定 `@Transactional`。** `KafkaIntegrationEventListener.java:82` 方法上是**裸** `@Transactional`,
未指定 `transactionManager`。当上下文存在多个事务管理器(如 Kafka `KafkaTransactionManager` + 数据库
`DataSourceTransactionManager`)时,Spring 按默认/主 bean 解析,**inbox 去重写与业务处理可能加入非预期的事务**
(或本该同一 DB 事务的两步落在不同管理器上),破坏"inbox 去重与业务副作用同事务"的原子性预期。

## 根因(第一性)

1. **观察 vs 期望**:期望"传输 starter 拥有线格式/消费语义 → 它也应拥有并隔离**匹配的**基础设施(序列化器、
   错误处理、事务边界)";实际"这些都搭 Boot 全局默认,责任外推给应用,且**反向污染**应用自身的 Kafka 用途"。
2. **最小机制**:starter 复用 Boot 的 `KafkaTemplate` / 默认 `ConcurrentKafkaListenerContainerFactory` /
   全局 `CommonErrorHandler` / 主事务管理器——省事,但这些都是**全局单例语义**,一旦被 starter 占用/配置,
   就不再属于 aipersimmon 通道私有。
3. **真根因**:抽象泄漏——组件掌握了契约(String value + `ce_` 头)与消费语义(inbox 去重 + 重试/DLT),却不封装
   这些语义**唯一决定**的那部分基础设施,反而与使用方共享全局面。
4. **危害分级**:(a) 是易用性 + 隐蔽 footgun;(b)(c) 更实——会**波及使用方自己的 Kafka 消费**(错误处理策略被强加、
   事务边界被错配),这是"库污染宿主"而非仅"库自己配不全"。

## 复现(test-first)

- **(a) 序列化器**:装配 `messaging-kafka` + Boot Kafka 但**不设** `spring.kafka.*-serializer` → `ProducerFactory`
  缺序列化器,发送失败;另一用例把 value-serializer 设成 `JsonSerializer`,断言消费端解析失败(二次编码)。修复后
  (starter 自带专属 String 通道)两种配置都不再影响 aipersimmon。
- **(b) 错误处理器**:上下文里除 aipersimmon 外再声明一个用**默认** container factory 的业务 `@KafkaListener`,断言
  它的错误处理被 aipersimmon 的 `DefaultErrorHandler`(不可重试直投 DLT)接管——修复后业务 listener 用自己的
  container factory,不受影响。
- **(c) 事务管理器**:装配两个 `PlatformTransactionManager`(Kafka + DB),断言 `onMessage` 的 inbox 写与业务处理
  未落在预期 DB 事务;修复后(显式限定 DB transactionManager)两步同事务。

## 修复

starter **拥有并隔离**自己的传输基础设施,全部 `@ConditionalOnMissingBean`,并让 dispatcher/listener **按名注入
专属 bean**(而非全局):

- 专属 `ProducerFactory`/`KafkaTemplate<String,String>` 与 `ConsumerFactory`,序列化/反序列化器**固定为 String**;
- 专属 `ConcurrentKafkaListenerContainerFactory`(承载 aipersimmon 的 error handler + 并发/重试设置),
  `@KafkaListener` 显式 `containerFactory = "..."`,**不占用** Boot 默认 factory;
- error handler 只绑定到该专属 factory,不作为全局 `CommonErrorHandler` 影响其他 listener;
- `@Transactional` 显式限定数据库 `transactionManager`(或按名),保证 inbox 去重与业务处理同一 DB 事务。

如此:aipersimmon 通道自洽、开箱即用;且与应用自身其它 Kafka 用途(可能用别的序列化器/错误策略/事务管理器)
**互不干扰**。

## 关联

- [[plan-00006-middleware-integration]]
- [[decision-00014-cloudevents-integration-event-contract]](线格式:String value + `ce_` 头;消费侧 inbox 去重 + 重试/DLT)
