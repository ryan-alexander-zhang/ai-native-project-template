---
id: issue-00030-single-topic-fanout-all-consumers-see-all-events
type: issue
role: main
status: open
parent: plan-00006-middleware-integration
---

# 单 topic + 消费桥全量进程内重投:每个消费者收到所有事件

## 问题(现状,file:line 为证)

- **等级:Medium(隔离性/可扩展性)**。
- 所有集成事件类型进**同一条 topic**:`KafkaMessagingProperties` 的 `topic` 默认
  `aipersimmon.integration-events`,无按类型/上下文路由。
- `KafkaIntegrationEventListener` 消费该 topic 后,经 inbox 去重、reconstruct envelope,把**每一条**事件用
  `ApplicationEventPublisher` **进程内重投**;各 `@EventListener` 靠 payload 类型自行过滤。于是本次单体自消费下,
  三个 BC 互相收到彼此的**全部**集成事件。
- `decision-00014` §7 已把"按类型 topic 路由"明确记为"未落地扩展点"。

## 根因(第一性)

1. **观察 vs 期望**:期望"消费者只订阅它关心的事件";实际"所有事件一条 topic、全量重投、消费端过滤"。
2. **最小机制**:单 topic + 单消费桥 + filter-at-consumer,是最省心的默认。
3. **真根因**:路由被推迟为扩展点。小规模无碍;规模化后代价显现:
   - 无 topic 级隔离/授权/保留策略(不同事件类的合规/留存需求不同);
   - 每个消费者反序列化每一条(含与己无关的),CPU/带宽浪费;
   - 无法按事件类型独立扩缩消费者、独立设置并发/重试/DLT;
   - 一条 topic 混所有类型(分区 key=聚合 subject,保证 per-aggregate 有序,但不相关聚合共享同一 topic 的运维面)。

## 复现

n/a(设计观察:单体自消费下每个 `@EventListener` 都会看到全部类型的事件,仅靠类型匹配丢弃)。

## 修复/建议(增强)

把路由做成一等配置:出站按 `@EventType`(或上下文)映射到 topic(`OrderPlaced → ordering.events` 等),入站按需
订阅相应 topic;保留"单 topic"为默认以兼容 monolith-first。这与 [[issue-00028-broker-transport-on-single-deployable-monolith]]
讨论中提到的"混合传输/按事件路由"缺口同源——两者可合并到一份 `messaging-kafka` 路由设计里(出站分流 + 入站选择性
重投 + inbox 统一去重)。

## 关联

- [[plan-00006-middleware-integration]]
- [[decision-00014-cloudevents-integration-event-contract]](§7 路由为未落地扩展点)
- [[issue-00028-broker-transport-on-single-deployable-monolith]]
