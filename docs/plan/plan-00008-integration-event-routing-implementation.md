---
id: plan-00008-integration-event-routing-implementation
type: plan
role: main
status: resolved
parent: design-00006-integration-event-routing
---

# 集成事件逐事件路由落地：`@Externalized` + `RoutingOutboxDispatcher` + 多 topic

把 [[design-00006-integration-event-routing]] 从设计落成代码：集成事件传输从"装了 Kafka 就全上 broker"细化到
**逐事件 opt-in**——默认 LOCAL（进程内 outbox+inbox），只有显式标注 `@Externalized` 的事件才外发到**命名 topic**，
未标注者永不碰 broker。承接 [[decision-00006-integration-event-transport-selection]]（三传输、单 dispatcher）并按
[[design-00006-integration-event-routing]] 四项已定决策（D1 注解 / D2 Level 2 / D3 显式 opt-in / D4 PG 同事务原子）实现。
前置 [[plan-00007-aggregate-persistence-mybatis-plus]]（聚合落 PG）已 resolved，D4 硬前提成立。

**验收锚点**：
1. **无 Kafka**：不引入 `messaging-kafka` → 全 LOCAL（方式二 outbox+inbox），行为不变。
2. **有 Kafka + 有标注**：标注 `@Externalized` 的事件外发到其 topic 并经消费桥读回；未标注事件只走进程内，永不进 broker。
3. **有 Kafka + 无标注**：全 LOCAL（Kafka 装配存在但闲置），启动打一条 WARN，**不** fail 启动、**不**静默全外发。
4. **不双投**：每个事件对本地 `@EventListener` 恰好一条投递路径——LOCAL 经进程内重投，EXTERNAL 只经消费桥；inbox 只守桥这一路。
5. **多 topic**：不同 `@Externalized` target 落不同 topic，消费桥只订阅被外发的 topic 集合，各自 `<topic>.DLT`。
6. 三个下游（`multi-module` / `microservice` / `integration-events-over-kafka` 样例）补 `@Externalized` 后各自 reactor `mvn verify` 全绿。

**铁律**：不改 `IntegrationEvents` port API（§七非目标）；不改任何业务 `@EventListener` handler；`@EventType` 保持纯逻辑契约，
外发是**独立**注解。发布方仍只调 `IntegrationEvents.publish()`，全程不感知 reach。

## 一、Design（机制）

### 1.1 现状 → 目标

| 维度 | 现状（as-is） | 目标（to-be） |
| --- | --- | --- |
| reach 粒度 | 应用级全局单选（`@ConditionalOnMissingBean` 三选一互斥） | **逐事件** reach：LOCAL（默认）/ EXTERNAL(topic) |
| 装了 Kafka | `KafkaOutboxDispatcher` 成唯一 dispatcher → 每条事件都上 Kafka | `RoutingOutboxDispatcher` 按 reach 分流；未标注只进程内 |
| topic | 单条 `aipersimmon.integration-events`，全量订阅、消费桥全量重投 | 每 `@Externalized` target 一条命名 topic；桥只订被外发的 topic |
| 外发身份 | 无（隐式全外发） | 事件上的 `@Externalized`（契约级）+ `${property}` 解析 topic（部署级） |

### 1.2 关键机制（对齐 design §4）

- **`@Externalized`（新，`aipersimmon-ddd-integration`）**：`@Target(TYPE) @Retention(RUNTIME)`，`String value()` = 目标 topic，
  支持 `${property:default}` 占位（解析留到装配层，契约里只声明"要外发 + 逻辑目标"）。缺省该注解 = LOCAL。与 `@EventType`
  同层但**独立**——传输/部署关注点不污染 [[decision-00014-cloudevents-integration-event-contract]] 的契约身份（D1）。
  静态读取器（仿 `IntegrationEvent.eventTypeOf`）：`Externalized.targetOf(Class)` → `Optional<String>`（原始 target，未解析）。
- **`RoutingOutboxDispatcher`（新，`aipersimmon-ddd-messaging-kafka`）**：relay 仍只注入**一个** `OutboxDispatcher`，
  但装了 Kafka 时它是路由器，内部持**进程内腿**（复用 `InProcessOutboxDispatcher`）与 **Kafka 腿**（`KafkaOutboxDispatcher`）：
  - 由 `(message.type, message.version)` 经 `IntegrationEventCatalog` 反查事件类 → 读 `@Externalized` → 得 reach。
  - **LOCAL** → 进程内腿（`ApplicationEventPublisher` 重投）。
  - **EXTERNAL(topic)** → Kafka 腿发到解析后的 topic；**不**再额外进程内直投（本地投递只经消费桥，避免双投，design §4.3 核心不变量）。
  - reach/topic 在启动时预解析成 `(type,version) → 解析后 topic` 映射（`${}` 用 `Environment`/`StringValueResolver` 解析一次），
    dispatch 时只查表。
- **多 topic 出站**：`KafkaOutboxDispatcher` 的 topic 从"构造期固定"改为"**逐消息**"——路由器算出 topic 传入。保留 `ce_*`
  binary binding、key=subject、bounded send（[[decision-00014-cloudevents-integration-event-contract]] 不变）。
- **多 topic 入站**：`KafkaIntegrationEventListener` 的 `@KafkaListener(topics=...)` 从单 topic 改为**被外发 topic 集合**
  （SpEL 引用一个暴露 `String[]` 的 bean）。inbox 按 `ce_id` 去重不变；`<topic>.DLT` 由 recoverer 按 `record.topic()` 逐 topic 派生（已有）。
- **闲置 WARN（D3）**：装了 Kafka 但零 `@Externalized` → 不注册消费桥（空 topic 集合会让 `@KafkaListener` 启动失败），
  改为启动打一条 WARN；路由器全部走 LOCAL 腿。既不静默全外发，也不 fail 启动。
- **ArchUnit（可选强化）**：新增规则——`@Externalized` 只能标在 `IntegrationEvent` 上、`value()` 非空；并入 `all()`。

### 1.3 装配矩阵（对齐 design §五）

| 装配 | dispatcher | 行为 |
| --- | --- | --- |
| 无 `messaging-kafka` | `LoggingOutboxDispatcher` 或（`dispatch=in-process`）`InProcessOutboxDispatcher` | 全 LOCAL（现状不变） |
| 有 `messaging-kafka` + 有标注 | `RoutingOutboxDispatcher` | 混合：标注者外发、其余 LOCAL；桥订被外发 topic |
| 有 `messaging-kafka` + 无标注 | `RoutingOutboxDispatcher`（Kafka 腿闲置） | 全 LOCAL + 启动 WARN；不注册消费桥 |

`RoutingOutboxDispatcher` 仍 `@ConditionalOnMissingBean(OutboxDispatcher.class)` 且 messaging-kafka autoconfig 排在
outbox core 之前——沿用今天"Kafka 顶替默认"的确定性装配，只是顶替物从 `KafkaOutboxDispatcher` 换成路由器。使用者仍可定义
自己的 `OutboxDispatcher`/`IntegrationEventCatalog` 覆盖。

## 二、Tasks（分阶段）

> 全程 test-first。P1 是库机制核心，P2 是库测试，P3–P5 是下游迁移（相互独立）。每阶段 `mvn -q verify`（对应 reactor）作红线。

- **P1 — 库机制（`aipersimmon-ddd-integration` + `aipersimmon-ddd-messaging-kafka`）**
  - `Externalized.java`（注解 + 静态 `targetOf`）落 `-integration`；`package-info` / Javadoc 讲清"契约级要不要外发 vs 部署级发去哪"。
  - `RoutingOutboxDispatcher.java` 落 `-messaging-kafka`：持进程内腿 + Kafka 腿 + 预解析 reach 映射；LOCAL/EXTERNAL 分流；无双投。
  - `KafkaOutboxDispatcher` 改为逐消息 topic（新增 `dispatch(message, topic)`；保留旧单 topic 构造以兼容/测试）。
  - `KafkaIntegrationEventListener` 的 `@KafkaListener` 订阅被外发 topic 集合（SpEL 引用 topics bean）。
  - `AipersimmonDddMessagingKafkaAutoConfiguration`：注册 `RoutingOutboxDispatcher`（替代裸 `kafkaOutboxDispatcher`）+ 被外发 topic 集合 bean +
    消费桥仅在集合非空时注册 + 零外发 WARN。`KafkaMessagingProperties` 视需要保留 `topic` 作可被 `${}` 引用的默认值（不再是路由键）。
  - （可选）ArchUnit 规则 + fixture。

- **P2 — 库测试**
  - `RoutingOutboxDispatcher` 单测：LOCAL 走进程内、EXTERNAL 走 Kafka（且不进程内直投）、未知类型、`${}` 解析、reach 映射构建。
  - EmbeddedKafka 集成测试：逐事件路由端到端——标注事件经其 topic 往返、未标注事件不进 broker、无双投、多 topic 各自订阅、闲置 WARN + 不注册桥。
  - 更新既有 `messaging-kafka` 单/集成测试以适配路由（原单 topic 断言）。

- **P3 — 迁移 `multi-module`（design §五 一次性迁移）**
  - 给真正跨上下文事件补 `@Externalized`（ordering/inventory/payment 各 api 的 8 个契约，按上下文分 topic 演示 §4.4，或统一引用现 `topic` 属性——落地时定）。
  - `start/application.yml`：单 topic 属性退化为可选默认；核对消费桥订阅、DLT。业务 handler 与发布方不改。
  - reactor `mvn -pl start -am verify`（真实 PG + Kafka 容器）全绿；纯进程内回归仍绿。

- **P4 — 迁移 `microservice`（真跨进程，功能性必需）**
  - `contracts` 的 3 个事件（OrderPlaced / StockReserved / StockReservationFailed）补 `@Externalized`——否则两服务无法通信。
  - 两服务 + e2e 的 `*.properties` 核对；`shop.integration-events` 单 topic 可经 `${}` 保留或拆分。e2e 全绿。

- **P5 — 迁移 `integration-events-over-kafka` 样例**
  - `ReservationPlaced` 补 `@Externalized`（否则 EmbeddedKafka 消费桥不再触发）；how-to Javadoc / `application.properties` 同步。样例 reactor 绿。

- **P6 — 回归与文档回填**
  - 各 reactor `mvn verify` 全绿；`design-00006` 状态与实现记录回填；本 plan 落 as-built；核对 [[decision-00006-integration-event-transport-selection]]
    的"dispatcher 三选一/composite"叙述是否需注记（路由器即内置 composite 的一种）。

## 三、验收路径

1. 库 reactor `mvn -pl aipersimmon-ddd-messaging-kafka -am verify` 绿：单测 + EmbeddedKafka 覆盖 §验收锚点 2–5。
2. `multi-module`：`docker compose up -d` 后 `POST /orders` 履约/补偿两流跑通；kafka-ui 见**被外发**事件在其 topic、未外发事件不出现；inbox 去重；无双投。
3. `microservice` e2e：两服务经命名 topic 通信、订单跨服务履约。
4. `integration-events-over-kafka` 样例 EmbeddedKafka 端到端绿。
5. 关掉 Kafka（不引 starter）跑 `reliable-integration-events` 式进程内回归绿——全 LOCAL 行为不变。
6. 造一个"装 Kafka 但零 `@Externalized`"场景：启动 WARN、不注册桥、全 LOCAL。

## 四、关联

- [[design-00006-integration-event-routing]]（父；本 plan 实现其全貌，D1–D4）
- [[decision-00006-integration-event-transport-selection]]（三传输、单 dispatcher；路由器是其"自定义 composite"叙述的内置化）
- [[decision-00014-cloudevents-integration-event-contract]]（`@EventType`/subject=key/`ce_*`；§7 topic 路由扩展点在此落地）
- [[plan-00006-middleware-integration]]（现场：single-topic 全外发，本 plan 迁移之）、[[plan-00007-aggregate-persistence-mybatis-plus]]（D4 前置，已 resolved）
- [[issue-00028-broker-transport-on-single-deployable-monolith]]、[[issue-00030-single-topic-fanout-all-consumers-see-all-events]]（驱动，本 plan 解）
- [[samples-not-reference]]（样例仅演示，非设计权威；但须随库默认变更保持绿）

## 五、已定 / 开放决策

已由 design-00006 定：D1 注解、D2 Level 2、D3 显式 opt-in、D4 PG 同事务原子（前置已就绪）。本 plan 落地口径补充：
- **迁移范围**：库机制 + **全部三个下游**（multi-module / microservice / kafka 样例）同步补 `@Externalized`，不留红。
- **单/多 topic**：微服务倾向按上下文命名 topic（降暴露面，坐实 §4.4）；monolith 可按上下文拆或经 `${topic}` 保留单 topic——各 app 落地时定，机制两者皆支持。
- **无 reach 列**：reach 在 dispatch 时由类反查 `@Externalized` 得，**不**给 `aipersimmon_outbox` 加列（不动 outbox 多方言 DDL 副本）。

## 实施记录（as-built，2026-07-21）

**已交付且全绿（本 plan 的核心，design-00006 落地）：**

- **库机制（P1/P2）**：
  - `-integration`：新增 `@Externalized(String value)`（`@Target(TYPE)`，`${property}` 由装配层解析）+ `IntegrationEvent.externalizedTarget(Class)` 静态读取器（仿 `eventTypeOf`，present 时校验非空）；`package-info` 补 reach 说明。
  - `-outbox`：抽出可复用 `IntegrationEventScanner`（`AutoConfigurationPackages` + `scan-packages`），catalog 构建改用之；无行为变化。
  - `-messaging-kafka`：`RoutingOutboxDispatcher`（进程内腿 = `InProcessOutboxDispatcher`，Kafka 腿 = `KafkaOutboxDispatcher`，`ExternalizedRoutes` 反查 reach，EXTERNAL 不再进程内直投——无双投）；`KafkaOutboxDispatcher` 改为**逐消息 topic**（`dispatch(message, topic)`，不再 `implements OutboxDispatcher`）；`@KafkaListener(topics = "#{@externalizedRoutes.topics()}")` 订被外发 topic 集；autoconfig 注册 `externalizedRoutes`（空则 WARN、不 fail）+ 路由器（顶替裸 kafka dispatcher）+ 消费桥/错误处理仅在 `OnExternalizedEventsCondition`（有 ≥1 外发事件）时注册；`KafkaMessagingProperties.topic` 降为"可被 `@Externalized("${...}")` 引用的默认值"。
  - 测试：`RoutingOutboxDispatcherTest` / `ExternalizedRoutesTest`（单测）；`AutoConfigurationWiringTest` 改写为路由模型（含"零外发→不注册桥"）；`KafkaDeadLetterIntegrationTest` 适配（事件 `@Externalized` + pin routes）；**新增 `routing.EventRoutingIntegrationTest`（EmbeddedKafka 端到端）**：逐事件路由、EXTERNAL 恰好一次经桥回投（无双投）、多 topic 各自落、LOCAL 只进程内不进 broker。整模块 30 tests 绿。
  - `-integration` 11、`-outbox` 114、`-outbox-jdbc` 20 全绿。
- **multi-module 迁移（P3）**：8 个跨上下文事件补 `@Externalized`，**按上下文分 topic**（`ordering.events` / `inventory.events` / `payment.events`，坐实 §4.4）；`start/application.yml` 去掉单 topic 属性、订正注释；业务 handler / 发布方零改。`mvn -pl start -am verify` 在**真实 Postgres 18.1 + Kafka 容器**上全绿（OrderingFlowTest / PaymentCompensationFlowTest / OutboxAtomicityTest）。

**未做（经确认 revert，非 design-00006 范畴）：**

落地中发现 `microservice` 与 `integration-events-over-kafka` 样例在**改动前的 HEAD 即已 RED**，且原因**早于 design-00006**（是 [[decision-00013-command-context-and-causation-propagation]] / [[decision-00014-cloudevents-integration-event-contract]] 迁移债，与 decision-00014 as-built 自述"modulith / microservice 未在本次复验范围"一致）：

- **microservice**：`schema.sql` 从未建 outbox-jdbc relay `@SchedulerLock` 所需的 `shedlock` 表 → relay 每次轮询 `BadSqlGrammar([INSERT INTO shedlock...])` → outbox 永不外发 → e2e 订单恒 `PENDING`（确定性红）。补 `shedlock` 后又暴露一处**静默的消费侧 dead-letter**（可重试异常，未记栈，成因未定）。
- **integration-events-over-kafka 样例**：`ReservationService` 仍调 decision-00013 之前的旧 `publish(...)` 签名（**不编译**），`ReservationPlaced` 缺 decision-00014 强制的 `@EventType`。

按用户决策，这两处的所有触碰**已全部 revert**，恢复到 HEAD 原样。它们的迁移（先补 decision-00013/00014 债，再补 design-00006 的 `@Externalized`，并查那处消费失败）另立后续，不在本 plan。**本 plan 交付物只含库 + multi-module，皆绿。**
