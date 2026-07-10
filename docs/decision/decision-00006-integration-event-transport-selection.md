---
id: decision-00006-integration-event-transport-selection
type: decision
role: main
status: active
parent:
---

# 集成事件传输:三种方式、确定性装配、monolith-first 默认

固化"集成事件怎么发"的传输选型策略。机制细节由 [[design-00001-aipersimmon-ddd-and-scaffold]]
（§5.6 传输总览、§5.7 events-spring、§5.8 outbox、§5.14 messaging-kafka）承载;
本文记录**策略与选型依据**——提供哪几种方式、默认走哪种、按什么信号升级、如何装配——
而不重复机制。承接 [[analysis-00002-domain-vs-integration-events]]（为何跨 BC 用集成事件)
与 [[analysis-00001-domain-event-publishing]]（发布/outbox 底座)。

## Context

发布方**只调 `-application` 里的 `IntegrationEvents.publish()` 这一个 port**,不感知传输。
跨边界发事件的核心张力有三条,决定了不存在"唯一正确"的传输:

1. **与聚合变更的原子性**:直接发事件会出现"库改了但事件没发出"或"事件发了但事务回滚"的
   双写不一致。outbox 把事件与聚合变更**写进同一事务**,消除这个缺口。
2. **耦合与部署形态**:同进程模块化单体不需要 broker;独立部署的微服务才需要。
3. **运维成本与交付语义**:同步进程内最简单但无重试、无持久;经 broker/outbox 得到
   at-least-once,代价是需要消费端幂等。

monolith-first 原则下,不应一上来强制 broker;但要让**升级路径平滑**(换传输不改业务代码)。

## Decision

**在唯一的 `IntegrationEvents` port 背后提供三种可插拔传输,由 classpath + 属性确定性装配,
默认最省(进程内),按信号逐级升级。**

| 方式 | 实现(bean) | 机制 | 交付语义 |
| --- | --- | --- | --- |
| **一 进程内同步** | `SpringIntegrationEvents`(`-events-spring`) | `ApplicationEventPublisher` 直接投给进程内 `@EventListener`,无 outbox、无异步 | 同事务、同步;无重试/无持久 |
| **二 进程内异步 + outbox** | `OutboxWriter` + `InProcessOutboxDispatcher` | 同事务写 `aipersimmon_outbox` → `@Scheduled` relay 轮询 → 按 `type` 反序列化后进程内重投 | at-least-once(配 `Inbox` 幂等) |
| **三 broker + outbox** | `OutboxWriter` + `KafkaOutboxDispatcher`(`-messaging-kafka`) | 同事务写 outbox → relay → 发 Kafka topic(阻塞等 ack 才置 `sent`)→ 消费端桥接重投 | at-least-once(消费端 `Inbox` 去重) |

**装配是确定性的,不靠使用者手工挑 bean:**

- **outbox 在 classpath → 走 outbox**。`-events-spring` 的方式一用
  `@ConditionalOnMissingBean(IntegrationEvents)` + `@ConditionalOnMissingClass(OutboxWriter)`
  守卫,**仅当 outbox 不存在时兜底**,保证"引入 outbox 即走 outbox"。
- **方式二/三只差 dispatcher**。两者都用 `OutboxWriter` 写库 + `OutboxRelay` 轮询,区别仅在
  relay 背后的 `OutboxDispatcher`:默认 `LoggingOutboxDispatcher`(只记日志的占位)、
  属性 `aipersimmon.ddd.outbox.dispatch=in-process` 切进程内重投、引入 `-messaging-kafka` 则
  Kafka dispatcher `@ConditionalOnMissingBean` 顶替默认。
- **dispatcher 契约与存储正交**。dispatcher 选择在存储无关的 `aipersimmon-ddd-outbox`(core);
  写库后端可选 `-outbox-jdbc` 或 `-outbox-mybatis-plus`(同表结构可互换);`-messaging-kafka`
  依赖 core,故可与任一存储组合。**方式二/三下消费者需显式引入恰好一个 outbox 存储 starter。**
- 使用者始终可定义自己的 `IntegrationEvents` / `OutboxDispatcher` bean 覆盖以上默认。

## 选型信号

按下列信号逐级升级,**不跳级**;传输升级是"换 starter + 属性",业务代码(只调 port)不动。

| 信号 | 方式一 同步 | 方式二 进程内异步+outbox | 方式三 broker+outbox |
| --- | --- | --- | --- |
| 部署形态 | 单进程,可容忍同步耦合 | 模块化单体(一个可部署单元) | 独立部署的多服务 |
| 双写原子性 | 不涉及(不落库) | **需要**(同事务写 outbox) | **需要** |
| 生产者延迟 | 与消费者同步耦合 | 只提交 outbox 行,快 | 只提交 outbox 行,快 |
| 跨进程/语言 | 否 | 否 | **是** |
| 幂等要求 | 无 | 消费端 `Inbox` | 消费端 `Inbox` |
| 运维承受力 | 只需应用本身 | 关系库 + 调度 | 关系库 + 调度 + broker |

极长流程/人工审批/可视化编排等超出 at-least-once + 定时重投的需求,属传输之上的编排问题,
见 [[analysis-00007-saga-process-manager]] 的 durable-execution 逃生舱,不在本决策范围。

## 装配速查

```
# 方式一 进程内同步
deps: aipersimmon-ddd-events-spring
（无 outbox 时自动生效)

# 方式二 进程内异步 + outbox
deps: aipersimmon-ddd-outbox-jdbc | aipersimmon-ddd-outbox-mybatis-plus   （选一个存储)
props: aipersimmon.ddd.outbox.dispatch=in-process
消费端: aipersimmon-ddd-inbox-jdbc | -inbox-mybatis-plus   （幂等,建议)

# 方式三 broker + outbox
deps: aipersimmon-ddd-messaging-kafka
    + aipersimmon-ddd-outbox-jdbc | -outbox-mybatis-plus   （显式选一个存储)
props: aipersimmon.ddd.messaging.kafka.consumer.enabled=true   （启用消费桥)
消费端: aipersimmon-ddd-inbox-jdbc | -inbox-mybatis-plus   （去重)
```

outbox / inbox 表由消费者自行建(Flyway/Liquibase);各存储 starter 附**非自动执行**的样例
DDL(`META-INF/aipersimmon-ddd/*-schema.sql`)。

## Consequences

**正向**
- 业务代码只依赖 `IntegrationEvents` port,传输在装配层切换,升级不改代码。
- 确定性装配消除"引了 outbox 却还走同步"的隐式歧义。
- dispatcher 与存储解耦后,broker starter 与存储后端自由组合(jdbc / mybatis-plus)。

**负向 / 注意**
- 方式二/三是 **at-least-once**:消费端必须幂等(`Inbox`),否则重投导致重复副作用。
- `-messaging-kafka` 不再传递带入存储后端,**消费者必须显式选一个 outbox 存储 starter**——
  否则缺少 `IntegrationEvents`(`OutboxWriter`)bean 而启动失败(见 §5.14 与相应样例)。
- 方式一无持久/无重试:进程崩溃即丢事件;只适合可容忍的进程内同步场景。
- 同时引入两个同类存储 starter(如 jdbc + mybatis-plus)属误用;各类只应引入一个。
- **dispatcher 三选一,不可叠加**:relay 只注入单个 `OutboxDispatcher`,故 logging / in-process /
  broker 互斥。三者都带 `@ConditionalOnMissingBean` 守卫(broker starter 另排在 core dispatch
  之前),因此**同时开 `dispatch=in-process` 与引入 Kafka 时,broker 确定性胜出**、不再报
  `NoUniqueBeanDefinitionException`。
- **要"同时投递"就用自定义 composite**:若需一份事件既进程内投又发 broker(扇出),或按
  `message.type()` 分流,**自定义一个 `OutboxDispatcher` bean** 组合内置实现即可——内置 dispatcher
  均 `@ConditionalOnMissingBean`,一旦自定义就全部让位。仍是单份 outbox(原子性不变);扇出下任一腿
  失败会整行重投、已成功腿重发,at-least-once 语义与幂等要求不变。

## Sources

内部:

- [[design-00001-aipersimmon-ddd-and-scaffold]] §5.6 传输总览、§5.7 events-spring、
  §5.8 outbox(含存储无关 core 抽出与 MyBatis-Plus 变体)、§5.14 messaging-kafka。
- [[analysis-00002-domain-vs-integration-events]] —— 领域事件 vs 集成事件、跨 BC 用集成事件。
- [[analysis-00001-domain-event-publishing]] —— 事件发布与 outbox 底座。
- [[analysis-00007-saga-process-manager]] —— 长流程编排与 durable-execution 逃生舱(本决策范围之外)。
- scaffold-samples:`reliable-integration-events`(outbox + 进程内)、
  `integration-events-over-kafka`(outbox→Kafka→inbox→进程内,EmbeddedKafka 端到端)。

外部:

- Chris Richardson, microservices.io —— *Transactional outbox* / *Polling publisher*。
  https://microservices.io/patterns/data/transactional-outbox.html
- Chris Richardson, microservices.io —— *Idempotent consumer*。
  https://microservices.io/patterns/communication-style/idempotent-consumer.html
