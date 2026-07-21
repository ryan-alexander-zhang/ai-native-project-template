---
id: issue-00030-single-topic-fanout-all-consumers-see-all-events
type: issue
role: main
status: open
parent: plan-00006-middleware-integration
---

# 入站消费桥订阅全部外发 topic 并全量进程内重投:无选择性订阅

> **更新**:本 issue 原记"所有事件进同一条 topic、无路由";其**出站**一半已被 [[design-00006-integration-event-routing]]
> 解决(逐事件路由:`@Externalized` 事件发往各自命名 topic)。剩下的、仍 open 的是**入站**一半——消费桥对所有外发
> topic 一把抓、全量重投、消费端过滤。标题保留旧名以维持 id 稳定。

## 问题(现状,file:line 为证)

- **等级:Medium(隔离性/可扩展性;增强,非 correctness)**。
- **出站已按事件路由**:`RoutingOutboxDispatcher.dispatch`(`RoutingOutboxDispatcher.java:44-51`)按 `(type, version)` 查
  `ExternalizedRoutes`,`@Externalized` 事件发往**各自命名 topic**、LOCAL 事件只走进程内。故不同事件可落不同 topic。
- **入站仍是"全订阅 + 全量重投 + 消费端过滤"**:
  - `KafkaIntegrationEventListener` 的单个 `@KafkaListener` 订阅
    `topics = "#{@externalizedRoutes.topics()}"`(`KafkaIntegrationEventListener.java:79-81`)——即
    `ExternalizedRoutes.topics()`(`ExternalizedRoutes.java:42-45`)返回的**所有外发 topic 的去重全集**,由**同一个消费组**消费;
  - 每条记录经 inbox 去重、reconstruct 后一律 `publisher.publishEvent(...)` **进程内重投**(`KafkaIntegrationEventListener.java:97`);
  - 各 `@EventListener` 靠 payload 类型**自行过滤**。
- 于是:一个服务即便只关心部分事件,也会**订阅并反序列化**它 classpath 上所有 `@Externalized` topic 的每一条;单体自
  消费下每个 `@EventListener` 都会看到全部外发类型(靠类型匹配丢弃无关的)。

## 根因(第一性)

1. **观察 vs 期望**:期望"消费者只订阅它关心的 topic/事件";实际"消费桥把外发 topic 全集当成一个订阅,全量重投,消费端过滤"。
2. **最小机制**:单 listener + 单消费组 + `topics()` 全集 + filter-at-consumer,是 monolith-first 下最省心的默认。
3. **真根因**:出站路由已一等化,但**入站的"选择性订阅"与"按 topic 独立的容器调优"被推迟**。小规模无碍;规模化后代价显现:
   - 无法让某服务只订阅它关心的 topic → 反序列化/带宽浪费在无关事件上;
   - 单 listener/单容器 → 无法按事件类型独立设并发/重试/DLT、独立扩缩消费者;
   - 无 topic 级隔离/授权/保留策略的消费侧对应物(出站虽已分 topic,入站又全量拉回)。

## 复现

n/a(设计观察:消费桥订阅 `externalizedRoutes.topics()` 全集并逐条 `publishEvent`;单体自消费下每个 `@EventListener` 都会
看到全部外发类型,仅靠类型匹配丢弃)。

## 修复/建议(增强)

按"便宜且高价值"到"重"排序,分档;保留"订阅全集 + 进程内重投"为 monolith-first **默认**,不破坏单可部署单元现状。

**1)类型短路 — ✅ 已实现(默认开启)。** `KafkaIntegrationEventListener.onMessage` 在 inbox 之前判断:某 `(type, version)`
若在 catalog 里**已知**、却**没有任何本地** `@EventListener<EventEnvelope<该类型>>`,则**直接跳过**——不写 inbox、不反序列化、
不重投。安全性:没有 handler ⟹ 没有副作用要与 inbox 原子化,跳过纯属丢弃一条本就会被丢弃的事件;offset 照常前进。
- 实现:`LocallyHandledEventTypes.scan(beanFactory)` 启动时扫 `@EventListener` 形参的 `EventEnvelope<T>` 收集本地类型集合;
  **保守**——凡遇 raw/通配 `EventEnvelope`、`classes()` 声明、无法解析的形参等,一律判 `handlesAll`(**绝不短路**),宁可多处理不可漏处理。
- **未知类型(不在 catalog)不短路**:仍走 reconstruct → DLT,保留 `decision-00014` 的"未知 (type,version) → DLT"契约。
- 开关:`aipersimmon.ddd.messaging.kafka.consumer.skip-locally-unhandled`(默认 `true`);若应用用非 `@EventListener` 的
  程序化 `ApplicationListener` 消费,置 `false` 关闭短路。
- 消除的是 **(a) 同一 topic 上本地无人接的类型** 的浪费,与订阅粒度正交。

**3)调 `spring.kafka.listener.concurrency` — ✅ 已在脚手架默认(`multi-module/start/application.yml: 3`)。** 让不同 partition
分到不同线程,缓解"一条瞬时失败的事件在退避期间拖住其他事件"。**只改示例,不改库**(库沿用 Boot 默认 1,不强加给使用者)。

**2)选择性订阅(方案一,跨服务隔离)— 仍 open。** 允许消费者只订阅外发 topic 的**子集**(按上下文/属性,如
`consumer.topics` 或按 BC),而非无条件订阅 `topics()` 全集。主要在**多服务拆分**后兑现价值(一个服务不订它不处理的 topic
→ (b) 类的跨服务浪费与跨 topic 阻塞被挡在服务边界外);对单体内部帮助有限,故暂缓。

**4)per-topic 容器(方案二,YAGNI)— 仍 open。** 仅当某 topic 确实需要**差异化 SLA**(独立并发/重试/DLT、独立扩缩)时,才为
不同 topic 建各自的 listener 容器——且**不复制 handler**,而是程序化循环注册容器、共用同一处理逻辑。默认不引入。

**topic 粒度:保持 per-context,不做 per-type 默认。** Kafka 只在 partition 内保序,当前分区 key=聚合 subject 使同一聚合的
多类型事件严格有序;若拆成"一事件类型一 topic",跨类型顺序会被打破(可能先见 Cancelled 再见 Placed)。业界默认即
topic-per-aggregate/context + 用 `ce_type` 分发(aipersimmon 现状已符合)。个别"无序、量级/保留/合规差异大"的事件可用现有
`@Externalized("其专属topic")` **按需定向**拆出,无需结构性改动。

进度:出站分流(design-00006)+ 类型短路(#1)+ 并发默认(#3)+ inbox 统一去重 均已就位;**本 issue 保持 open**,余项为
入站选择性订阅(#2,多服务时做)与 per-topic 容器(#4,差异化 SLA 时做)。

## 关联

- [[plan-00006-middleware-integration]]
- [[design-00006-integration-event-routing]](已解决出站逐事件路由;入站选择性订阅为后续)
- [[decision-00014-cloudevents-integration-event-contract]](§7 topic 路由的原始扩展点)
