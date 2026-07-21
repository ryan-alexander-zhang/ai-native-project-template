---
id: issue-00048-inbox-dedup-key-omits-cloudevents-source
type: issue
role: main
status: open
parent: plan-00006-middleware-integration
---

# Inbox 去重 key 缺少 CloudEvents source:ce_id 仅同源唯一,跨源同 id 会被误判为重复

## 问题(现状,file:line 为证)

- **等级:Medium(正确性,罕见触发)**。
- Inbox 去重 key 是 `(consumer, message_key)`:DDL 主键
  `aipersimmon-ddd-inbox/.../inbox/postgresql/V1__aipersimmon_inbox.sql`(`PRIMARY KEY (consumer, message_key)`);
  查询/插入 `JdbcInbox.java:28-30`(`WHERE consumer = ? AND message_key = ?`)。
- 而 `message_key` 就是 **ce_id**:`KafkaIntegrationEventListener` 用 `require(record, IntegrationEventHeaders.ID)`
  取 `ce_id` 后 `inbox.alreadyProcessed(eventId)`。
- 但按 CloudEvents 规范,**`id` 只在同一 `source` 内唯一**;全局唯一的是 **`id` + `source`**。envelope 里 `source`
  (ce_source)本就有(`EventEnvelope.source()`),却**没进 key**。

后果:两个**不同 source**恰好用了相同 `id`、发给同一 consumer 时,第二条会被 `(consumer, message_key)` 命中而**误判为
重复、静默丢弃**。

## 根因(第一性)

1. **观察 vs 期望**:期望"去重键 = 事件的全局唯一标识";实际"键只用了 `id`,把 CloudEvents 的**同源唯一**当成了全局唯一"。
2. **最小机制**:单一生产者/单体自消费下 `id`(通常 UUID)不会撞,于是 `source` 被省掉了;多生产者聚合到同一 inbox 时
   假设失效。

## 复现(test-first)

- 两个不同 `ce_source`、相同 `ce_id` 的事件先后投给同一 consumer;断言:现状第二条被当重复跳过(handler 不执行);
  修复后两条都被处理(因 key 含 source 而不相撞)。

## 修复/建议

- 去重键改为 `(consumer, source, message_key)`:`Inbox.alreadyProcessed` 增加 `source` 入参(listener 已能拿到
  `require(record, IntegrationEventHeaders.SOURCE)`),`JdbcInbox`/`MybatisPlusInbox` 的 SQL 与 DDL 主键同步加 `source`。
- **注意改动面**:inbox 表 DDL 有多处副本(库迁移 + 各 scaffold 的 `schema.sql`),需一起改(与 process-manager 四表 DDL
  的多副本同步问题同类);`Inbox` 接口签名变动影响 `-inbox-jdbc`、`-inbox-mybatis-plus` 两个适配器与消费桥调用点。

## 关联

- [[plan-00006-middleware-integration]]
- [[decision-00014-cloudevents-integration-event-contract]](CloudEvents:id 同源唯一,id+source 全局唯一)
