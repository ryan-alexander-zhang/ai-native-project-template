---
id: issue-00027-outbox-atomicity-broken-by-in-memory-aggregate
type: issue
role: main
status: open
parent: plan-00006-middleware-integration
---

# 事务性 outbox 骑在内存聚合上,原子性保证形同虚设

## 问题(现状,file:line 为证)

- **等级:High**(库宣称的核心保证在本示例里实际不成立)。
- `multi-module/ordering/.../PlaceOrderHandler.java`:`:100` `orders.save(order)` 写的是
  `InMemoryOrders`(`ordering-infrastructure/.../InMemoryOrders.java:15` 是一个 `ConcurrentHashMap`),
  `:103` `integrationEvents.publish(...)` 写的是 Postgres 上的 `aipersimmon_outbox`(经 `OutboxWriter`)。
  handler 无 `@Transactional`,内存 map 也不参与任何事务。inventory/payment 的同类 handler 同构。
- 于是"聚合变更"与"outbox 行写入"**分处两个介质、无共同事务**:内存 map 立即可见且不可回滚,outbox 行在
  独立的 JDBC 事务里提交。

## 根因(第一性)

1. **观察 vs 期望**:期望"聚合变更与其对外事件**要么一起成功、要么一起失败**"(transactional outbox 的定义);
   实际二者独立提交,不存在原子边界。
2. **最小机制**:outbox 模式成立的**唯一前提**是"聚合写 + outbox 写落在同一个事务性数据库的同一事务中"
   (`decision-00006` Context 第 1 条明写:outbox 把事件与聚合变更写进同一事务以消除双写不一致)。内存聚合根本
   没有事务,无法与 Postgres 的 outbox 事务合并。
3. **真根因**:脚手架选择**内存聚合持久化**——这在传输为方式一(进程内同步、不声称原子性)时无害;但本次把
   传输升级为 outbox(方式三),等于**做出了一个它在此持久化模型下无法兑现的保证**。故障窗口:`orders.save` 后、
   outbox 提交前进程崩溃 → 聚合"活着"但事件永不发出;或 outbox 事务回滚 → 事件没发但内存 map 已改。二者皆双写不一致。
4. **隐蔽性**:因为内存 map "提交"是瞬时且无回滚的,**没有任何测试能自然捕获**——这正是它危险的地方。

## 复现(test-first)

概念复现:在 `PlaceOrderHandler` 的 `orders.save(order)` 与 outbox 提交之间注入故障(抛异常/杀进程),观察
聚合状态与 `aipersimmon_outbox` 行不一致。**自动化回归依赖修复本身**:只有当聚合与 outbox 同处一个事务性
DataSource、handler 在一个 `@Transactional` 内时,才存在可断言的原子边界(注入 outbox INSERT 失败 → 断言聚合
INSERT 一并回滚)。因此该缺陷的失败测试与其修复(见下)是同一件事。

## 修复

**把三个聚合持久化到与 outbox 同一个 Postgres**(JDBC 仓储替换 `InMemory*`),command handler 在单个
`@Transactional`(或 CQRS unit-of-work 的 DB 事务)内执行,使"聚合 INSERT/UPDATE + outbox INSERT"同事务提交。
这正是既有 [[plan-00006-middleware-integration]] 六.显式排除里记为后续 `plan-00007`("聚合落 PostgreSQL")的工作——
本 issue 表明它**不是锦上添花,而是 outbox(方式二/三)有任何意义的前提**。

在完成前,应明确:当前 `multi-module` 的 outbox/Kafka 可靠性是**名义上的**——传输层可靠,但源头"聚合↔事件"这一步
不原子。(替代下策:若坚持内存聚合,则应退回方式一进程内同步,不引 outbox,也就不做无法兑现的原子性声明。)

## 关联

- [[plan-00006-middleware-integration]](暴露现场;六.已记为 plan-00007 前置)
- [[decision-00006-integration-event-transport-selection]](Context 第 1 条:outbox 同事务原子性)
- [[decision-00016-durable-runtime-staged-message-identity]]
