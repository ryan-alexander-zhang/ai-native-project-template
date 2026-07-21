---
id: issue-00029-kafka-transport-starter-leaks-global-transport-infrastructure
type: issue
role: main
status: resolved
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

**(b) 全局 `CommonErrorHandler` 与其他 listener 相互干扰(双向失效)。** starter 注册
`AipersimmonDddMessagingKafkaAutoConfiguration.java:189-194`
`@ConditionalOnMissingBean(CommonErrorHandler.class) DefaultErrorHandler kafkaErrorHandler(...)`;而 aipersimmon
自己的 `@KafkaListener`(`KafkaIntegrationEventListener.java:79`)**未指定** `containerFactory`,用的是 Boot 默认的
`kafkaListenerContainerFactory`。Boot 的 `ConcurrentKafkaListenerContainerFactoryConfigurer` 用 **`errorHandler.ifUnique(...)`**
把 `CommonErrorHandler` 装到默认工厂——"**唯一才装**"。由此派生**两个方向**的问题:

1. **使用方有 listener 但未自定义 error handler**:aipersimmon 的是唯一 `CommonErrorHandler` → 被 Boot 装到默认工厂 →
   使用方与集成事件无关的业务 listener 也**被强加** aipersimmon 的退避/DLT 策略(含把
   `UnknownIntegrationEventException`/`MalformedIntegrationEventException`/`JsonProcessingException` 标记为不可重试直投
   DLT,`:224-227`)——这些异常语义对业务 listener 毫无意义。
2. **使用方自己定义了一个 `CommonErrorHandler`**:旧代码的 `kafkaErrorHandler` bean 带 `@ConditionalOnMissingBean(CommonErrorHandler.class)`,
   于是 aipersimmon 的 handler **根本不被创建**;Boot 把使用方那个唯一的 handler 装到默认工厂,aipersimmon 自己的 listener
   也用默认工厂 → 它**静默继承**了使用方的错误策略,失去自己承诺的退避/DLT(坏消息不再进 `<topic>.DLT`)。
   (注:机制是"因 `@ConditionalOnMissingBean` 而不创建",不是"两个 handler 令 `ifUnique` 都不装"——但**静默降级 aipersimmon
   自身可靠性**这一风险成立,且比第 1 面更隐蔽。)

**(c) Listener 用未限定 `@Transactional`(条件触发,触发后 High)。** `KafkaIntegrationEventListener.java:82` 方法上是
**裸** `@Transactional`,未指定 `transactionManager`。inbox **确实在这个事务里写库**:`JdbcInbox.alreadyProcessed`
(`aipersimmon-ddd-inbox-jdbc/.../JdbcInbox.java:44-48`)做 `SELECT COUNT` + `INSERT aipersimmon_inbox`,`Inbox` 接口与
`JdbcInbox` 的 Javadoc 都要求它「runs in the caller's transaction … commits and rolls back together with the processing」。
故这个 `@Transactional` **必须**绑定到该 `DataSource` 的事务管理器,inbox 插入才能与 handler 的写在**同一 DB 事务**里原子
提交/回滚。精确的条件与后果:

- **单一 `DataSourceTransactionManager` 的应用(如当前 `multi-module` 样例):今天可正常工作**——裸 `@Transactional`
  解析到唯一 TM 恰是那个 DB TM。故这不是现网 live bug,是**健壮性/隔离缺陷**。
- **一旦应用引入第二个 `PlatformTransactionManager`**(如设 `spring.kafka.producer.transaction-id-prefix` 产生
  `KafkaTransactionManager`,或多数据源/JPA TM),裸 `@Transactional` 按 primary/默认解析,可能**绑错**。绑错时
  `JdbcTemplate` 连接不在 DB 事务内 → 每条 `INSERT` **自动提交** → handler 失败时 **inbox 行已提交、副作用却回滚/未发生**
  → 重投被 inbox 抑制 → **事件静默丢失**。此后果远重于"未加入预期事务"。

## 根因(第一性)

1. **观察 vs 期望**:期望"传输 starter 拥有线格式/消费语义 → 它也应拥有并隔离**匹配的**基础设施(序列化器、
   错误处理、事务边界)";实际"这些都搭 Boot 全局默认,责任外推给应用,且**反向污染**应用自身的 Kafka 用途"。
2. **最小机制**:starter 复用 Boot 的 `KafkaTemplate` / 默认 `ConcurrentKafkaListenerContainerFactory` /
   全局 `CommonErrorHandler` / 主事务管理器——省事,但这些都是**全局单例语义**,一旦被 starter 占用/配置,
   就不再属于 aipersimmon 通道私有。
3. **真根因**:抽象泄漏——组件掌握了契约(String value + `ce_` 头)与消费语义(inbox 去重 + 重试/DLT),却不封装
   这些语义**唯一决定**的那部分基础设施,反而与使用方共享全局面。
4. **危害分级**:(a) 是易用性 + 隐蔽 footgun;(b) 是**双向**污染——要么把 aipersimmon 策略强加给宿主 listener,要么被
   宿主的 handler 挤掉、静默降级 aipersimmon 自身 DLT;(c) 单 TM 下不触发,多 TM 下绑错可致**消息静默丢失**。(b)(c) 都是
   "库与宿主相互干扰"而非仅"库自己配不全"。

## 复现(test-first)

- **(a) 序列化器**:装配 `messaging-kafka` + Boot Kafka 但**不设** `spring.kafka.*-serializer` → `ProducerFactory`
  缺序列化器,发送失败;另一用例把 value-serializer 设成 `JsonSerializer`,断言消费端解析失败(二次编码)。修复后
  (starter 自带专属 String 通道)两种配置都不再影响 aipersimmon。
- **(b) 错误处理器,两个方向都要测**:①上下文里除 aipersimmon 外再声明一个用**默认** container factory 的业务
  `@KafkaListener`,断言它的错误处理被 aipersimmon 的 `DefaultErrorHandler`(不可重试直投 DLT)接管;②应用自己声明一个
  `CommonErrorHandler`,断言旧代码因 `@ConditionalOnMissingBean` 而**不创建** aipersimmon 的 handler、其 listener 静默继承
  应用策略、坏消息不再进 `<topic>.DLT`。修复后:aipersimmon 用专属 container factory + 只绑该工厂的 error handler
  (最后 `setCommonErrorHandler` 覆盖 configurer 可能装上的),两个方向都不再相互干扰;`AutoConfigurationWiringTest` 断言
  无全局 `CommonErrorHandler` bean、专属工厂存在。
- **(c) 事务管理器**:装配两个 `PlatformTransactionManager`(如 Kafka + DB),让裸 `@Transactional` 绑到非 DB TM,
  断言 handler 抛异常后 `aipersimmon_inbox` 行**仍被提交**(inbox 与副作用未原子回滚,重投被抑制);修复后(显式限定
  DB transactionManager)handler 失败时 inbox 行随之回滚,重投可被正确重放。

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

## 解决(已实现)

**隔离的本质不是再建一套 Kafka,而是不与宿主争抢同一组全局策略——且不过度隔离。** 连接、SSL/SASL、listener 并发/
ack/poll/observation 等**沿用宿主/Boot 默认**;只**覆盖**由 aipersimmon 协议决定的三处:

- **(a) 线格式**:`aipersimmonKafkaTemplate` 与专属容器工厂的 consumer factory **复用应用自己的 `ProducerFactory`/
  `ConsumerFactory` 配置**(`getConfigurationProperties()` 拷一份——因此天然继承其连接,含 service connection 的
  bootstrap servers,不会退回 `localhost:9092`),只把 (de)serializer **覆盖为 String**。dispatcher 注入
  `@Qualifier("aipersimmonKafkaTemplate")`。样例 `multi-module/start/application.yml` 与切片测试均**不再**配
  `spring.kafka.*-serializer`,`KafkaDeadLetterIntegrationTest`/`EventRoutingIntegrationTest` 在零 serializer 配置下端到端通过。
- **(b) 错误处理**:专属 `aipersimmonKafkaListenerContainerFactory` 只 `setCommonErrorHandler` 到自己身上,**不再**暴露
  全局 `CommonErrorHandler` bean;`@KafkaListener(containerFactory = "aipersimmonKafkaListenerContainerFactory")` 固定绑定。
  其余容器设置沿用 spring-kafka 默认。`AutoConfigurationWiringTest` 断言无全局 `CommonErrorHandler`、专属工厂存在。
- **(c) 事务边界**:listener 用 `TransactionOperations`(**不新建 TM,只显式解析宿主已有的**)包裹 inbox+处理,去掉裸
  `@Transactional`。解析:属性 `consumer.transaction-manager` > 唯一 TM > **有 inbox 但无 TM 则启动失败**(inbox 插入必须
  事务化)/ 无 inbox 才 `withoutTransaction` > 多 TM 且未指定则**启动失败**。`AutoConfigurationWiringTest` 覆盖「多 TM
  fail-loud」「按名解析」「有 inbox 无 TM fail-loud」。

> 注:早期实现曾手搭 ProducerFactory/ConsumerFactory 并引入 `KafkaConnectionDetails`/customizer/
> `ConcurrentKafkaListenerContainerFactoryConfigurer`,属**过度隔离**——既增加面又会漏掉服务连接。最终改为「复用宿主
> 工厂配置、只覆盖三处」,风险最小。

## 关联

- [[plan-00006-middleware-integration]]
- [[decision-00014-cloudevents-integration-event-contract]](线格式:String value + `ce_` 头;消费侧 inbox 去重 + 重试/DLT)
