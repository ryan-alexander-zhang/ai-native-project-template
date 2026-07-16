---
id: issue-00014-inbox-record-composite-key-tableid
type: issue
role: main
status: resolved
parent: issue-00003-messaging-delivery-reliability
---

# MyBatis 收件箱 `InboxRecord` 的 `@TableId` 误标在 `message_key` 上,与复合主键 `(consumer, message_key)` 不一致

`aipersimmon_inbox` 的主键是复合的 `(consumer, message_key)`——`consumer` 把去重限定在单个消费应用,几个服务
共享同一张表时互不误抑制。但 MyBatis-Plus 实体 `InboxRecord` 把 `@TableId(type = IdType.INPUT)` 标在
`messageKey` **单列**上,等于告诉 MP「`message_key` 唯一标识一行」——这与复合主键的语义相悖。

## 问题(现状,file:line 为证)

- **等级:Low(潜在,未触发)**。
- `InboxRecord.java:20`(修复前)`@TableId(type = IdType.INPUT)` 仅在 `messageKey`;真实主键见
  `inbox-schema.sql:12` `PRIMARY KEY (consumer, message_key)`。
- 当前生产路径**只用** `insert` / `selectCount(wrapper)` / cleanup 的 `delete(wrapper)`(见
  `MybatisPlusInbox.java:38-44`、`InboxCleanup.java:37-38`),三者都显式带 `consumer` + `message_key`,所以
  **暂无功能 bug**。
- 隐患在 `BaseMapper` 的 **id 方法**:`selectById` / `deleteById` / `updateById` 会按 `@TableId` 生成
  `WHERE message_key = ?`,**丢掉 `consumer` 维度**。一旦有人(未来代码、脚本、误用)调 `deleteById("evt-1")`,
  会**跨消费者删除**——把另一个服务对同一 `message_key` 的去重记录一并清掉,导致其重放/重复消费。
- 复现坐实:两消费者各记 `evt-1` 后 `inboxMapper.deleteById("evt-1")` **不报错、静默命中** message_key(修复前
  会连删两行)。

## 根因(第一性)

实体的**身份**是它的主键。这行记录的身份是复合自然键 `(consumer, message_key)`;`message_key` 单独并不唯一
(跨消费者会重复)。把 `@TableId` 强加在一个**非唯一**列上,是给 ORM 一个错误的身份声明——凡是依赖「id = 身份」
的通用方法都会据此以错误的粒度操作数据。MP 原生无法表达复合 `@TableId`,那么**正确做法不是找一列凑数,而是
声明「本实体无单列 id」**,从而让所有访问都走显式的、带 `consumer` 的条件包装器。

## 修复

移除 `InboxRecord` 上的 `@TableId`(及 `IdType`/`TableId` import):

1. 实体不再声称任何单列是其身份;`message_key` 回归普通列。
2. MP 因此**不生成** id 方法(`selectById`/`deleteById`/`updateById`)——调用即 `BindingException`,把「按
   message_key 单列寻址」这种误用**从静默数据损坏变成显式失败**。
3. `insert` / `selectCount(wrapper)` / `delete(wrapper)` 全部不依赖 `@TableId`,行为不变(`IdType.INPUT` 本就
   不自动生成 id,移除后 insert 等价)。
4. javadoc 记明「无 `@TableId`——身份是复合键,只经 consumer-scoped wrapper 访问」的理由。

## 边界

- 纯 MyBatis 后端问题;JDBC 收件箱(`JdbcInbox`,纯 SQL 显式 `consumer = ? AND message_key = ?`)无实体、不受影响,
  两后端语义一致。
- 不改表结构、不改去重算法(读-先-于-插 + 复合 PK 兜底)与 consumer 作用域语义。

## 影响模块

`aipersimmon-ddd-inbox-mybatis-plus`(仅 `InboxRecord` 注解)。无 schema 变更。

## 验收标准(GWT)

- **AC-1**:`InboxRecord` 无单列 `@TableId`;`deleteById`/`selectById`/`updateById` 不可用(调用即抛),不能按
  `message_key` 单列跨消费者操作。
- **AC-2**:正常路径(`insert`、per-consumer `selectCount`、cleanup `delete`)与去重/consumer 作用域行为不回归;
  Spring 上下文正常启动。

## 验证结果

先复现后修,库模块全绿。

- **复现(红)**:`InboxMybatisPlusTest#idBasedAccessDoesNotSilentlyIgnoreTheConsumerScope`:两消费者各记 `evt-1`,
  `assertThrows(inboxMapper.deleteById("evt-1"))`。修复前 `deleteById` 静默执行(按 message_key)、不抛 →
  `Expected java.lang.Exception to be thrown, but nothing was thrown` → 红。
- **修复后(绿)**:移除 `@TableId` 后 MP 不生成 id 方法,`deleteById` 抛 `BindingException`;两行去重记录均未被
  误删(`selectCount(null)==2`)。即 AC-1。
- **回归**:`InboxMybatisPlusTest` 4 测试全绿(含 `dedupIsScopedPerConsumer`、首投/重投、autoconfig),上下文正常
  启动,`BUILD SUCCESS`。即 AC-2。

AC-1 / AC-2 达成,本 issue `resolved`。

## 关联

- [[issue-00003-messaging-delivery-reliability]] —— inbox 幂等与 outbox DLQ 的三层分离;本 issue 收口 MyBatis 实体
  身份声明与复合主键一致。
