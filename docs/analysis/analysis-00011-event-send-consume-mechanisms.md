---
id: analysis-00011-event-send-consume-mechanisms
type: analysis
role: main
status: active
parent:
---

# 脚手架事件收发机制清单：领域事件 / 集成事件到底提供了多少种发送与消费方式

**范围：`aipersimmon-ddd/` 库源码本身**（不含 `bc-and-layer-samples`、`*-scaffold-samples` 等演示模块）。
本文回答一个具体问题——**当前 DDD 脚手架为领域事件、集成事件提供的发送与消费方式共有多少种**——
并逐一给出对应的类/接口与文件位置、同步/异步语义。

配套阅读:[[analysis-00001-domain-event-publishing]](领域事件发布/消费机制、可插拔 publisher）、
[[analysis-00002-domain-vs-integration-events]](两类事件的判定轴与大厂实践）、
[[analysis-00005-structure-2-event-flow-and-cqrs]](事件驱动完整链路 Outbox→Broker→Inbox + CQRS-lite）。

> 本文只清点**事件**(异步)的收发。跨 BC 的**同步调用**(非事件——读对方当前状态、快速失败）
> 是另一条通道,形状与边界见 [[decision-00015-cross-context-sync-query-via-gateway-acl]]。

## 结论先行

脚手架把事件明确分成**两个家族**,各自的收发方式如下:

1. **领域事件(进程内、同事务)**:发送 **1 种**(同步 `SpringDomainEvents`),消费 **1 种**
   (Spring `@EventListener` / `@TransactionalEventListener`,标注 `@DomainEventHandler`)。内置发布器只有同步一种;
   异步只能由消费端自行加 `@Async` 开启,脚手架不自带异步领域事件发布器。
2. **集成事件(跨上下文、经传输通道)**:统一入口端口 `IntegrationEvents.publish(event, CommandContext)`,底层可插拔——
   发送侧有 **3 条发布路径**(Spring 进程内 / JDBC Outbox / MyBatis-Plus Outbox),Outbox 落库后由 **3 种投递器**
   之一外发(Logging / In-Process / Kafka);消费侧有 **2 种接收方式**(进程内 `@EventListener`、Kafka 监听器),
   外加 **Inbox 幂等守卫**(两套存储后端)。
3. **外部 Broker 只有 Kafka**——脚手架不含 RabbitMQ / Redis / HTTP 集成事件传输(Redis 仅用于 `web-store`,与集成事件无关)。

---

## 一、领域事件(领域事件 · 进程内)

领域事件本身是**单一的同步机制**:发送 1 种 + 消费 1 种。

### 1.1 发送(1 种,同步)

| 环节 | 机制 | 文件 |
|---|---|---|
| 聚合内记录 | `AbstractAggregateRoot.registerEvent()` / `domainEvents()` / `clearDomainEvents()`,`transient List<DomainEvent>`,零框架依赖 | `aipersimmon-ddd/aipersimmon-ddd-core/.../core/model/AbstractAggregateRoot.java` |
| 保存时排空(端口) | `DomainEvents.publishAndClear(aggregate)`——由仓储/处理器在**命令事务内**、持久化聚合后立即调用 | `aipersimmon-ddd/aipersimmon-ddd-application/.../application/DomainEvents.java` |
| 具体发布器 | `SpringDomainEvents implements DomainEvents`,委托 Spring `ApplicationEventPublisher.publishEvent` | `aipersimmon-ddd/aipersimmon-ddd-events-spring/.../events/spring/SpringDomainEvents.java` |
| 自动装配 | `@Bean @ConditionalOnMissingBean(DomainEvents.class)`——应用可覆盖为自定义实现 | `.../events/spring/AipersimmonDddEventsAutoConfiguration.java` |
| 事务边界 | `TransactionCommandInterceptor`(`CommandInterceptor`,最内层 `ORDER=200`)把 handler 包进一个 `UnitOfWork` | `aipersimmon-ddd/aipersimmon-ddd-cqrs-spring/.../cqrs/spring/TransactionCommandInterceptor.java` |

**同步语义**:交付发生在调用者线程 + 同一事务,状态变更与已发布事件一起提交/回滚。
脚手架**不使用** Spring Data 的 `@DomainEvents` / `@AfterDomainEventPublication`,也**没有** ThreadLocal 式 `AggregateCollector`——
"保存点显式 drain"是刻意的设计选择(见 Javadoc)。

事件标记有两种等价写法(标记方式,非收发方式):
- `DomainEvent` 标记**接口**:`.../core/event/DomainEvent.java`
- `@DomainEvent` 标记**注解**:`.../core/annotation/DomainEvent.java`

### 1.2 消费(1 种)

- Spring `@EventListener`(同步,调用者线程/事务)或 `@TransactionalEventListener`(提交后),方法参数为 `DomainEvent`。
- 处理器类须标注 `@DomainEventHandler`(`.../application/DomainEventHandler.java`)。
- **ArchUnit 强制**(`aipersimmon-ddd/aipersimmon-ddd-archunit/.../archunit/AiPersimmonDddRules.java`):
  - `domainEventListenersShouldResideInApplicationOrDomain()`——监听器须在 `..application..` 或 `..domain..`;
  - `domainEventListenersShouldBeAnnotatedWithDomainEventHandler()`——其声明类须标 `@DomainEventHandler`。

**同步 vs 异步**:内置发布器只有同步;异步需消费端自行加 Spring `@Async`,脚手架源码内无异步领域事件发布器。

---

## 二、集成事件(集成事件 · 跨上下文)

统一发布端口:`IntegrationEvents.publish(IntegrationEvent event, CommandContext context)`
(`aipersimmon-ddd/aipersimmon-ddd-application/.../application/IntegrationEvents.java`)。
`CommandContext.of(EventEnvelope)` 把入站事件转成命令的因果上下文(ACL 转换点)。
事件信封 `EventEnvelope<T>`(`aipersimmon-ddd-integration/.../integration/EventEnvelope.java`)按 CloudEvents 属性建模。

### 2.1 发送:3 条发布路径

| # | 发布路径 | 传输 | 同步/异步 | 文件 |
|---|---|---|---|---|
| 1 | `SpringIntegrationEvents` | 进程内 Spring 事件,无 Outbox/Broker | **同步**(同线程/同事务) | `aipersimmon-ddd-events-spring/.../events/spring/SpringIntegrationEvents.java` |
| 2 | `OutboxWriter`(JDBC) | 事务性 Outbox 落库(`INSERT` 进 `aipersimmon_outbox`,与聚合变更同事务) | 落库同步、投递异步 | `aipersimmon-ddd-outbox-jdbc/.../outbox/jdbc/OutboxWriter.java` |
| 3 | `OutboxWriter`(MyBatis-Plus) | 事务性 Outbox 落库(第二套存储后端) | 同上 | `aipersimmon-ddd-outbox-mybatis-plus/.../outbox/mybatisplus/OutboxWriter.java` |

选路开关:events 自动装配只在 `@ConditionalOnMissingClass("...outbox.jdbc.OutboxWriter")` 时才用 `SpringIntegrationEvents`——
**引入 outbox starter 会自动把传输从进程内切走**。

### 2.2 发送:Outbox 落库后的 3 种投递器

Outbox 行由 `OutboxRelay`(`@Scheduled` 轮询)取出,交给以下 `OutboxDispatcher`(`aipersimmon-ddd-outbox/.../outbox/OutboxDispatcher.java`)之一:

| # | 投递器 | 行为 | 文件 |
|---|---|---|---|
| 4 | `LoggingOutboxDispatcher` | 仅打日志(**默认**,开箱即用) | `aipersimmon-ddd-outbox/.../outbox/LoggingOutboxDispatcher.java` |
| 5 | `InProcessOutboxDispatcher` | 重建 `EventEnvelope` 并经 `ApplicationEventPublisher` **异步在进程内重新发布**(Outbox 当异步传输) | `aipersimmon-ddd-outbox/.../outbox/InProcessOutboxDispatcher.java` |
| 6 | `KafkaOutboxDispatcher` | 发到 Kafka,**CloudEvents 二进制绑定**(`ce_` 头 + 分区键=subject),`.get()` 等待、失败即抛以便重试 | `aipersimmon-ddd-messaging-kafka/.../messaging/kafka/KafkaOutboxDispatcher.java` |

`OutboxRelay` 的生产级加固(JDBC 与 MyBatis-Plus 两套语义一致):**排序** `ORDER BY created_at, id`;**DLQ** `attempts < max-attempts`(默认 10);
**按 subject 挂起**避免同聚合后续事件越过卡住的那条;**ShedLock `@SchedulerLock`** 多实例只允许一个轮询。
配套 `OutboxCleanup`(`@Scheduled` + ShedLock,保留期默认 7 天,opt-in)。

### 2.3 消费:2 种接收方式 + Inbox 幂等

| # | 接收方式 | 说明 | 文件 |
|---|---|---|---|
| 7 | 进程内 `@EventListener`,参数 `EventEnvelope<TheEvent>` | 接收来自 Spring 发布器 / 进程内投递器 / Kafka 监听器转发的事件;**ArchUnit 强制放在 `..adapter..`** | (应用侧,ArchUnit 规则见 `AiPersimmonDddRules.integrationEventListenersShouldResideInAdapter()`) |
| 8 | `KafkaIntegrationEventListener`(`@KafkaListener` + `@Transactional`) | 跨进程 Kafka 消费:查 Inbox(键 `ce_id`)去重 → 经 `IntegrationEventTypeResolver` 解析 `ce_type` → 由 `ce_` 头重建 `EventEnvelope` → 经 `ApplicationEventPublisher` 转发为进程内事件 | `aipersimmon-ddd-messaging-kafka/.../messaging/kafka/KafkaIntegrationEventListener.java` |

**Inbox 幂等消费者**(`Inbox.alreadyProcessed(messageKey)`,`aipersimmon-ddd-application/.../application/Inbox.java`),两套后端:
- `JdbcInbox`——`aipersimmon-ddd-inbox-jdbc/.../inbox/jdbc/JdbcInbox.java`
- `MybatisPlusInbox`——`aipersimmon-ddd-inbox-mybatis-plus/.../inbox/mybatisplus/MybatisPlusInbox.java`

均按 `consumer` 列隔离(多服务共表),**PG-safe 先查后插**(先 `SELECT COUNT(*)` 再 `INSERT`,避免失败插入的约束冲突把整个 PostgreSQL 事务打断),
在调用者事务内运行。配套 `InboxCleanup`(`@Scheduled`,保留期默认 30 天,opt-in;幂等故不加锁)。

### 2.4 解耦与因果传播(横切)

- `IntegrationEventTypeResolver` / `RegistryIntegrationEventTypeResolver`(注册表 + 全限定名 `Class.forName` 兜底)——把消费方与生产方的类解耦。
- `IntegrationEventHeaders`——CloudEvents v1.0 Kafka 二进制绑定头名(`ce_id`/`ce_source`/`ce_type`/`ce_time`/`ce_subject` 等),生产者与消费者共享。
- `CommandContext`(`messageId`/`correlationId`/`causationId`/`traceId`)端到端传播因果链。

---

## 三、数量小结

| 家族 | 发送 | 消费 |
|---|---|---|
| **领域事件** | 1 种:同步 `SpringDomainEvents`(聚合 `registerEvent` → 保存时 `publishAndClear`) | 1 种:`@EventListener`/`@TransactionalEventListener`(`@DomainEventHandler`,`application`/`domain` 层) |
| **集成事件** | 3 条发布路径(Spring 进程内 / JDBC Outbox / MyBatis-Plus Outbox)+ Outbox 下 3 种投递器(Logging / In-Process / Kafka) | 2 种接收(进程内 `@EventListener`、Kafka 监听器)+ Inbox 幂等(两后端) |

- 领域事件内置**仅同步**;集成事件的异步交付集中在 Outbox(In-Process/Kafka 投递器)与 Kafka 消费侧。
- 外部 Broker **仅 Kafka**;无 RabbitMQ / Redis / HTTP 集成事件传输。

---

## Sources

- 领域事件发布/消费与可插拔 publisher 语义:[[analysis-00001-domain-event-publishing]]
- 两类事件"概念上永远区分"的判定轴与大厂实践:[[analysis-00002-domain-vs-integration-events]]
- Outbox→Broker→Inbox 完整链路与 CQRS-lite 读侧:[[analysis-00005-structure-2-event-flow-and-cqrs]]
- 库源码(逐文件,已读):`aipersimmon-ddd/` 下 `-core` / `-application` / `-integration` / `-cqrs` / `-cqrs-spring` /
  `-events-spring` / `-outbox` / `-outbox-jdbc` / `-outbox-mybatis-plus` / `-inbox-jdbc` / `-inbox-mybatis-plus` /
  `-messaging-kafka` / `-archunit` 各模块。
- 近期加固提交:`4a0e94b`(outbox/inbox 生产级加固:locking、PG-safe dedup、ordering、DLQ、retention)、
  `b45cf30`(显式 `CommandContext` + CloudEvents 集成事件)、`918dd6f`(保存时 drain 领域事件、移除 ThreadLocal `AggregateCollector`)。
