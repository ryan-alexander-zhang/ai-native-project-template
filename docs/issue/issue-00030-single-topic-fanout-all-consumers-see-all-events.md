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

把**入站订阅**也做成可配置,与已落地的出站路由对称:

- 允许消费者只订阅外发 topic 的**子集**(按上下文/属性,如 `consumer.topics` 或按 BC),而非无条件订阅 `topics()` 全集;
- 可选:为不同 topic 用**各自的 listener 容器**,以便按事件类型独立设并发/重试/DLT、独立扩缩;
- 保留"订阅全集 + 进程内重投"为 monolith-first **默认**,以不破坏单可部署单元的现状。

出站分流(已完成)+ 入站选择性订阅(本 issue)+ inbox 统一去重,合起来才是完整的按事件路由。

## 关联

- [[plan-00006-middleware-integration]]
- [[design-00006-integration-event-routing]](已解决出站逐事件路由;入站选择性订阅为后续)
- [[decision-00014-cloudevents-integration-event-contract]](§7 topic 路由的原始扩展点)
