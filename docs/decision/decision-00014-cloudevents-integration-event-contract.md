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
> 保序。消费端用可插拔 `IntegrationEventCatalog`(`(type, version)` → 本地类;无 FQCN 回退,未知即 DLT)替代硬 `Class.forName`,
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
2. **`IntegrationEvent` 声明契约身份**:`eventType()` / `eventVersion()`(逻辑类型 + schema 版本)与 `subject()`
   (聚合 id,可空 default)。逻辑类型与版本**必须以 `@EventType(name, version)` 注解显式声明**(见下方修订):
   `name` 标识业务事件,`version` = `dataschemaversion` = payload schema 修订;二者合成 **`(name, version)` 精确
   解析键**(payload schema 变化 → bump `version`;业务事实语义变化 → 换 `name`)。**每个 `(name, version)` 是独立
   契约**:默认扫描一个注解类注册一个 pair;消费者要继续消费/重放旧版本,须保留旧版本类或经自定义 catalog 提供
   映射(可把多版本映射到同一兼容类),**无隐式跨版本回退**(未注册的 pair 即 DLT)。`eventType()` / `eventVersion()`
   读注解、缺失即报错——**无简单类名回退、无硬编码版本**。`subject()` 仍是 default(可空)。**修订说明(见
   [[issue-00005-integration-event-logical-type-resolution]])**:本决策初稿曾定 `eventType()` 默认简单类名、
   可选覆盖,且发布器把信封 `version` 硬编码为 1;前者与命题一自相矛盾(覆盖成命名空间名后默认 resolver 反而解析
   不了),后者使 `dataschemaversion` 恒为 1、与事件无关。故改为 `@EventType(name, version)` 必填,信封 `version`
   取自注解。这是唯一新增的注解,承 [[decision-00009-event-type-markers-and-handler-contracts]] 的克制(不加
   handler 契约注解),仅把"它是版本化契约"这一身份落到类型上。
3. **消费端类型解析可插拔**:`IntegrationEventCatalog`(framework-free 接口,`Optional<Class> lookup(type, version)`)
   + 默认 `RegistryIntegrationEventCatalog`(`(type, version)` → 类的注册表)。默认实现由 outbox autoconfig
   **扫描应用包下的 `IntegrationEvent` 实现**,按各类 `(@EventType name, version)` 建表(与该类被发布时写线的
   `(ce_type, ce_dataschemaversion)` 同源)。**扫到未标 `@EventType` 的事件、或两个类声明同一 `(name, version)`,
   均启动即报错**(取代静默 `putIfAbsent`),另有 ArchUnit 规则在构建期先行拦截。**未知 `(type, version)` 入站
   一律进 DLT——无 FQCN 回退、不按类名猜测**(`lookup` miss 抛 `UnknownIntegrationEventException`;DLT 路由随
   [[issue-00003-messaging-delivery-reliability]] 落地)。`InProcessOutboxDispatcher` 与
   `KafkaIntegrationEventListener` 一律经 catalog 重建,不再 `Class.forName`。catalog bean 可被应用覆盖,用于动态
   注册 / 第三方事件 / 历史版本迁移——非默认路径。(原文"按简单类名建表 + FQCN 回退 + 零配置",已按
   [[issue-00005-integration-event-logical-type-resolution]] 修订为上述 `@EventType` 必填 + `(type, version)` 键 +
   无回退。)
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
   逻辑 `type` + catalog 让消费者映射到自己的类型;共享契约包经 `scan-packages` 纳入扫描(不再靠 FQCN 回退)(monolith-first,
   承接 [[decision-00006-integration-event-transport-selection]])。
- **命题二 —— 分区键必须是业务排序键。** Kafka 仅在分区内保序,分区由 key 决定;用每事件唯一的 `eventId` 作 key 等于
   放弃 per-aggregate 顺序。以聚合 `subject` 作 key 是 Kafka 的标准做法,也正是 CloudEvents Partitioning 扩展
   `partitionkey` 的用途。
- **命题三 —— 采用标准而非自造。** CloudEvents 是 CNCF 事实标准,逐条命中 source/type/subject/id/time + 因果/分区
   扩展,并有官方 Kafka binding;自造信封只会重复发明且更差互操作。

## Consequences

- 契约:`EventEnvelope` 增 `source`/`subject` 并把 `type` 变为逻辑类型;`IntegrationEvent` 增 `eventType()`/`subject()`;
   新增 `IntegrationEventCatalog` + `RegistryIntegrationEventCatalog` + `UnknownIntegrationEventException`(均在 `-integration`,framework-free)。
- 传输:`IntegrationEventHeaders` 改为 `ce_*` CloudEvents 头;`KafkaOutboxDispatcher` key=subject + ce 头;
   `KafkaIntegrationEventListener` 经解析器重建;`InProcessOutboxDispatcher` 经解析器重建。
- 装配:outbox autoconfig 扫描注册 `IntegrationEventCatalog`;发布器注入 `source` 属性。
- 存储:`OutboxMessage` 与 outbox 表增 `source`/`subject`。
- **supersede** [[decision-00006-integration-event-transport-selection]] 中"按 `type` 反序列化"的隐含 FQCN 语义:
   改为逻辑类型 + 解析器;传输选型策略本身不变。
- **解析器扫描可配**:默认解析器扫描应用的 `AutoConfigurationPackages`,并叠加
   `aipersimmon.ddd.integration.scan-packages`(逗号分隔)所列的包。后者用于集成事件位于应用包之外的拓扑——典型如
   两个微服务共享的 `contracts` 模块(不在各自 auto-config 包内)。若无此配置,共享-contracts 的消费端会
   `no integration event type registered for 'com.example.ordering.OrderPlaced.v1'`;配置后即解决。
- **`@EventType` 必填(按 [[issue-00005-integration-event-logical-type-resolution]] 修订)**:每个 `IntegrationEvent`
   须以 `@EventType` 声明逻辑类型,无回退、缺失即报错;新增 ArchUnit 规则 `integrationEventsShouldDeclareEventType()`
   并入 `all()` 构建期固化。仓库既有集成事件(库测试夹具 + 三脚手架契约)已补注解。
- scaffold:三变体的集成事件覆盖 `subject()` 返回 orderId(聚合键)、声明 `@EventType`;microservice 的 outbox schema
   增两列,且两个服务(含 e2e 的 `*-e2e.properties`)设 `scan-packages=com.example.contracts`。初稿三变体端到端全绿;
   `@EventType` 修订经**库 + multi-module** 复验(modulith / microservice 未在本次复验范围)。
- 遗留:按类型 topic 路由、tenant 扩展 —— 记为扩展点,未落地。

## Sources

内部:

- `aipersimmon-ddd/aipersimmon-ddd-integration/.../EventEnvelope.java`、`IntegrationEvent.java`、`EventType.java`、`IntegrationEventCatalog.java`。
- `aipersimmon-ddd/aipersimmon-ddd-messaging-kafka/.../IntegrationEventHeaders.java`、`KafkaOutboxDispatcher.java`、`KafkaIntegrationEventListener.java`。
- [[decision-00006-integration-event-transport-selection]]、[[decision-00013-command-context-and-causation-propagation]]、[[decision-00009-event-type-markers-and-handler-contracts]]、[[analysis-00002-domain-vs-integration-events]]。

外部:

- CloudEvents v1.0 Specification —— 核心属性 id/source/type/subject/time/datacontenttype。https://github.com/cloudevents/spec/blob/v1.0.2/cloudevents/spec.md
- CloudEvents Kafka Protocol Binding —— binary content mode、`ce_*` 头、Partitioning 扩展 `partitionkey`→消息 key。https://github.com/cloudevents/spec/blob/v1.0.2/cloudevents/bindings/kafka-protocol-binding.md
- Apache Kafka Producer 配置 —— 分区由消息 key 决定,同 key 同分区、分区内保序。https://kafka.apache.org/documentation/#producerconfigs
