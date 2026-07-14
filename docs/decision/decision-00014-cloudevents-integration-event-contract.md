---
id: decision-00014-cloudevents-integration-event-contract
type: decision
role: main
status: active
parent:
---

# 集成事件对外契约对齐 CloudEvents(逻辑类型 + 聚合分区键 + source)

固化"集成事件在传输上长什么样、类型怎么标识、分区/排序键是什么、带哪些元数据"。承接
[[decision-00006-integration-event-transport-selection]](三种传输、outbox 底座)与
[[decision-00013-command-context-and-causation-propagation]](envelope 承载 correlation/causation),
并**修正** decision-00013 之前 envelope 用 **Java 全限定类名(FQCN)作为事件类型** 的做法。

## 结论先行

> **集成事件的对外线格式对齐 [CloudEvents](https://cloudevents.io) v1.0(Kafka binary binding)。**
> `EventEnvelope` 的字段映射为 CloudEvents 属性:`eventId`→`id`、`source`→`source`(生产上下文)、
> `type`→`type`(**逻辑类型名,不再是 FQCN**)、`subject`→`subject`(聚合 id)、`occurredAt`→`time`,
> 扩展 `correlationid`/`causationid`/`traceid`/`partitionkey`;payload 为 record value,`content-type` 载
> `datacontenttype`。**Kafka 消息 key = 聚合 `subject`**(缺失才回退 `eventId`),保证同一聚合事件落同一分区、
> 保序。消费端用可插拔 `IntegrationEventTypeResolver`(逻辑类型 → 本地类;FQCN 回退)替代硬 `Class.forName`,
> 消费者不再被迫依赖生产者的 Java 类。

## Context

改造前(decision-00013 落地时)存在四个问题:
1. **FQCN 作事件类型**:`OutboxWriter` 把 `event.getClass().getName()` 写进 `EventEnvelope.type`,消费端
   `Class.forName(type)` 反序列化——消费者被迫拥有生产者的 Java 类,跨语言/独立演进不可能。且 `EventEnvelope`
   Javadoc 早已写"a **logical type name**",与实现自相矛盾。
2. **`eventId` 作 Kafka key**:key 每事件唯一 → 同一聚合的事件散落到不同分区 → 破坏 per-aggregate 顺序
   (Kafka 仅在分区内保证有序,分区由 key 决定)。
3. **`Class.forName` 反序列化**:同 1 的耦合,外加从 header 字符串加载任意类的健壮性问题。
4. **缺 source / subject(聚合键)**:消费者无从得知事件来自哪个上下文、属于哪个聚合。

`docs/reference` 语料对"事件信封元数据"支撑很弱(见 [[decision-00013-command-context-and-causation-propagation]]
Context),但这正是 **CNCF CloudEvents** 标准化的问题域——它定义了语言中立、可独立演进的事件信封属性,并有官方
Kafka binding。**有标准,采用标准。**

## Decision

1. **`EventEnvelope` 对齐 CloudEvents 属性**:新增 `source`(必填)、`subject`(可空,聚合 id);`type` 语义改为
   **逻辑类型**(不再 FQCN)。新增 `partitionKey()`:`subject` 优先,缺失回退 `eventId`。
2. **`IntegrationEvent` 声明契约身份**:新增两个可选 default 方法——`eventType()`(逻辑类型,默认简单类名,
   可覆盖为版本化/命名空间名如 `com.example.ordering.OrderPlaced.v1`)与 `subject()`(聚合 id,默认 null)。
   仍不加注解、不加 handler 契约(与 [[decision-00009-event-type-markers-and-handler-contracts]] 一致);这是把
   "它是版本化契约"落到接口上。
3. **消费端类型解析可插拔**:`IntegrationEventTypeResolver`(framework-free 接口)+ 默认
   `RegistryIntegrationEventTypeResolver`(逻辑类型 → 类的注册表,FQCN 回退)。默认实现由 outbox autoconfig
   **扫描应用包下的 `IntegrationEvent` 实现**按简单类名建表,零配置可用;共享 classpath / 共享契约包时 FQCN 回退兜底。
   `InProcessOutboxDispatcher` 与 `KafkaIntegrationEventListener` 一律经解析器重建,不再 `Class.forName`。
4. **Kafka = CloudEvents binary binding**:`KafkaOutboxDispatcher` 以 `subject`(缺失回退 eventId)为消息 key,
   CloudEvents 属性走 `ce_*` header(`ce_id`/`ce_source`/`ce_specversion`/`ce_type`/`ce_time`/`ce_subject` +
   扩展 `ce_correlationid`/`ce_causationid`/`ce_traceid`/`ce_partitionkey`),`content-type: application/json`;
   listener 反向解析。header 常量集中在 `IntegrationEventHeaders`。
5. **`source` 由配置提供**:属性 `aipersimmon.ddd.integration.source`(默认 `${spring.application.name:aipersimmon}`),
   各发布器(`OutboxWriter` jdbc/mybatis、`SpringIntegrationEvents`)注入后盖到信封。
6. **outbox 存储**:行增 `source`(NOT NULL)、`subject` 两列(jdbc + mybatis-plus,含参考 DDL 与测试 schema)。
7. **topic 与 tenant(边界)**:仍是单一可配 topic(按类型路由留作扩展点,不在本决策落地);tenant 非 CloudEvents 核心
   属性,作为应用级扩展(不出厂 null 字段)。

## Rationale

- **命题一 —— 类型必须是逻辑契约,不是 Java 类。** 发布语言(published language)的类型标识应稳定、语言中立;FQCN
   把内部实现细节泄漏成对外契约,违反本库"集成事件与内部模型解耦"的立场(analysis-00002 / EventEnvelope Javadoc)。
   逻辑 `type` + 解析器让消费者映射到自己的类型;FQCN 回退保留 monolith / 共享契约包的零配置便利(monolith-first,
   承接 [[decision-00006-integration-event-transport-selection]])。
- **命题二 —— 分区键必须是业务排序键。** Kafka 仅在分区内保序,分区由 key 决定;用每事件唯一的 `eventId` 作 key 等于
   放弃 per-aggregate 顺序。以聚合 `subject` 作 key 是 Kafka 的标准做法,也正是 CloudEvents Partitioning 扩展
   `partitionkey` 的用途。
- **命题三 —— 采用标准而非自造。** CloudEvents 是 CNCF 事实标准,逐条命中 source/type/subject/id/time + 因果/分区
   扩展,并有官方 Kafka binding;自造信封只会重复发明且更差互操作。

## Consequences

- 契约:`EventEnvelope` 增 `source`/`subject` 并把 `type` 变为逻辑类型;`IntegrationEvent` 增 `eventType()`/`subject()`;
   新增 `IntegrationEventTypeResolver` + `RegistryIntegrationEventTypeResolver`(均在 `-integration`,framework-free)。
- 传输:`IntegrationEventHeaders` 改为 `ce_*` CloudEvents 头;`KafkaOutboxDispatcher` key=subject + ce 头;
   `KafkaIntegrationEventListener` 经解析器重建;`InProcessOutboxDispatcher` 经解析器重建。
- 装配:outbox autoconfig 扫描注册 `IntegrationEventTypeResolver`;发布器注入 `source` 属性。
- 存储:`OutboxMessage` 与 outbox 表增 `source`/`subject`。
- **supersede** [[decision-00006-integration-event-transport-selection]] 中"按 `type` 反序列化"的隐含 FQCN 语义:
   改为逻辑类型 + 解析器;传输选型策略本身不变。
- **解析器扫描可配**:默认解析器扫描应用的 `AutoConfigurationPackages`,并叠加
   `aipersimmon.ddd.integration.scan-packages`(逗号分隔)所列的包。后者用于集成事件位于应用包之外的拓扑——典型如
   两个微服务共享的 `contracts` 模块(不在各自 auto-config 包内)。若无此配置,共享-contracts 的消费端会
   `no integration event type registered for 'OrderPlaced'`;配置后即解决。
- scaffold:三变体的集成事件覆盖 `subject()` 返回 orderId(聚合键);microservice 的 outbox schema 增两列,且两个服务
   (含 e2e 的 `*-e2e.properties`)设 `scan-packages=com.example.contracts`。**三变体端到端全绿**:multi-module(16)、
   modulith(3)、microservice(含跨 broker e2e 2)。
- 遗留:按类型 topic 路由、tenant 扩展 —— 记为扩展点,未落地。

## Sources

内部:

- `aipersimmon-ddd/aipersimmon-ddd-integration/.../EventEnvelope.java`、`IntegrationEvent.java`、`IntegrationEventTypeResolver.java`。
- `aipersimmon-ddd/aipersimmon-ddd-messaging-kafka/.../IntegrationEventHeaders.java`、`KafkaOutboxDispatcher.java`、`KafkaIntegrationEventListener.java`。
- [[decision-00006-integration-event-transport-selection]]、[[decision-00013-command-context-and-causation-propagation]]、[[decision-00009-event-type-markers-and-handler-contracts]]、[[analysis-00002-domain-vs-integration-events]]。

外部:

- CloudEvents v1.0 Specification —— 核心属性 id/source/type/subject/time/datacontenttype。https://github.com/cloudevents/spec/blob/v1.0.2/cloudevents/spec.md
- CloudEvents Kafka Protocol Binding —— binary content mode、`ce_*` 头、Partitioning 扩展 `partitionkey`→消息 key。https://github.com/cloudevents/spec/blob/v1.0.2/cloudevents/bindings/kafka-protocol-binding.md
- Apache Kafka Producer 配置 —— 分区由消息 key 决定,同 key 同分区、分区内保序。https://kafka.apache.org/documentation/#producerconfigs
