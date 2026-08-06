---
id: issue-00048-inbox-dedup-key-omits-cloudevents-source
type: issue
status: resolved
blocks: [plan-00006-middleware-integration]
---

# Inbox 去重 key 缺少 CloudEvents source:ce_id 仅同源唯一,跨源同 id 会被误判为重复

> **注（2026-08-06 补）**：本记录写于库同时并存 JDBC 与 MyBatis-Plus 两套存储后端的时期。
> `-persistence-jdbc`、`-outbox-jdbc`、`-inbox-jdbc`、`-process-manager-jdbc`、`-operation-log-jdbc`、
> `-web-store-jdbc`、`-starter-jdbc` 已全部删除（库只留 MyBatis-Plus 后端；web 边界存储由
> `-web-store-mybatis-plus` 承接）。因此下文带 `-jdbc` 的模块名、路径与 `file:line`，指的是当时的代码，
> 不是现在的树；它们作为当时的证据保留，未被改写成 MyBatis-Plus 的路径。

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

## 核查结论(在当前 HEAD 复核)

**确认成立。** 逐条复核无误:`Inbox.alreadyProcessed(String messageKey)` 只收一个键;三方言 DDL 主键均为
`PRIMARY KEY (consumer, message_key)`;`JdbcInbox` 的 `WHERE consumer = ? AND message_key = ?`;消费桥用
`ce_id` 单值调用。所以 A 源的 id `"1"` 落库后,B 源的**不同事件**若 id 也是 `"1"`,查询命中 ⟹ 判重 ⟹
`onMessage` 直接 return ⟹ **静默丢弃**,无异常、无 DLT、无日志。

还发现一处**文档层面同源错误**:`V2__add_tenant_id.sql` 的注释把 `message_key` 说成
「producer-assigned, **globally-unique** message id」——正是本 issue 要纠正的那个错误前提,已一并改掉。

## 修复(已实施)

去重键改为 `(consumer, source, message_key)`:

- `Inbox.alreadyProcessed(String source, String messageKey)`——**不留单参重载**:留着就等于把这个 bug
  作为一条可用路径继续提供。
- 三方言 V1 迁移加 `source VARCHAR(255) NOT NULL` 并进主键。MySQL 侧核过 InnoDB 3072 字节上限:
  `(128+255+128) × 4 = 2044`,注释里写明。
- `JdbcInbox` / `MybatisPlusInbox`(＋`InboxRecord` 新增 `source` 字段)同步。
- 消费桥把 `require(record, SOURCE)` **提前到去重之前**读取,并传给 `reconstruct` 复用(不重复读 header)。
  副作用是缺 `ce_source` 的记录现在在**写 inbox 之前**就被判为 malformed → DLT,比之前更干净。

**为什么改 V1 而不是加 V3**:V3 需要在三方言里各自 drop/add 主键,更关键的是**必须给既有行的 `source`
编造一个值**——而那个值不可知,编出来的行本来也无法正确去重。改 V1 避免伪造数据。框架尚未发布、无使用者,
Flyway 校验和变更的代价只落在可丢弃的本地库上;这是在「留一段"我们把键搞错了"的永久迁移史」与
「不伪造数据」之间选了后者。

顺带把 `-inbox-jdbc` / `-inbox-mybatis-plus` 测试里**手工枚举**借用迁移的 `schema-locations` 改成
`classpath*:.../V*.sql` 模式。这两个模块和 kafka 一样是**借用**它不拥有的 schema,枚举式引用正是
[[issue-00056-kafka-tests-pin-a-stale-inbox-schema]] 的成因;本次若不改,下一个 V3 会在这里重演。

## 验证结果

两个适配器各新增 `dedupIsScopedPerSource`:同 id 不同 source 的两条**都必须被处理**,各自的重投**都必须被判重**。
在旧键下第一条断言必然失败——这就是它守护的东西。`KafkaIntegrationEventListenerTest` 的 `InMemoryInbox`
桩改为按 `(source, key)` 去重:桩若只按 key 去重,就会掩盖这个键存在的全部理由。
`InboxCleanupTest` 的直插夹具被 `NOT NULL` 逮到并补齐 source——约束按预期发挥了作用。

`-inbox` / `-inbox-jdbc` / `-inbox-mybatis-plus` 全绿;框架全量 `install` 与样例 `verify` 均 BUILD SUCCESS。
