---
id: issue-00129-in-process-redelivery-was-not-deduplicated
type: issue
status: resolved
---

# 同一个 handler，走 Kafka 有人替它去重，走本地 relay 没有

2026-07-30 全面评审（P0）。四条投递保证主线里唯一一处"同一 API、不同保证"。

## 问题

集成事件的两条投递路径对 at-least-once 重投的处理不对称：

- **Kafka 桥**：`aipersimmon-ddd-messaging-kafka/.../KafkaIntegrationEventListener.java:135-148`
  在消费事务内做 `inbox.alreadyProcessed(source, eventId)` 去重——框架替 handler 挡掉重投。
- **outbox 进程内派发**：`aipersimmon-ddd-outbox-spring-boot-starter/.../InProcessOutboxDispatcher.java:56-70`
  完全不查 inbox（整个 starter 无一处引用 `Inbox`，已全模块 grep 核实），javadoc 只留了一句
  "handlers should be made idempotent (see the inbox)"。

## 根因（第一性）

- 期望行为：无论事件经哪条传输到达，`@EventListener` handler 看到的重投语义一致。
- 分歧机制：relay 的 dispatch 成功与 `markSent` 是两次提交，中间崩溃即重投；Kafka 路径的重投被
  inbox 事务性拦下，in-process 路径的重投直接再次进入 handler。
- 真根因：去重职责放在了**传输适配层**（Kafka 桥）而不是**投递语义层**，于是每新增一条传输都要
  各自记得做一遍。不是 handler 写错，也不是 outbox 原子性问题——写侧原子性是完好的。

后果：重复的 `ReserveStock` 等命令会被原样派发。scaffold 主代码里没有任何 `alreadyProcessed`
调用，即消费方以为"框架保证"的性质在本地 relay 路径上并不存在。

## 复现（先写失败测试）

outbox starter 集成测试：注册一个计数 handler，投递一条事件，在 `markSent` 前人为使 relay
失败一次（或直接对同一 outbox 行触发两次 dispatch），断言 handler 只执行一次。修复前该测试
应以"执行了两次"失败。

## 改法

`InProcessOutboxDispatcher` 构造器接受可选 `Inbox`（与 Kafka 桥同款签名），在
`TenantContext.runAs` 内、`publishEvent` 前做同一对键的去重；outbox starter 检测到 `Inbox`
bean 存在时自动接线。不存在 `Inbox` 时，启动日志明示 "in-process redelivery is NOT
deduplicated"，把隐含契约变成显式声明。

## 验证结果

2026-07-31 修复。要点比 issue 预测多一层：**inbox 检查必须与 handler 副作用同事务**（Kafka 桥
的 `@Transactional` 正是这个作用）——否则"先记 inbox、后处理失败"会让重投被误判为重复，
把重复问题换成丢事件问题，比不修更糟。因此：

- `InProcessOutboxDispatcher` 新增 `(Inbox, TransactionOperations)` 构造器，两者强制成对；
  dedup 键与 Kafka 桥同为 `(source, eventId)`，检查在 `TenantContext.runAs` 内、事务回调内。
- outbox starter：有 `Inbox` bean 自动接线（无 `PlatformTransactionManager` 则启动即拒绝——
  见上面的"更糟"论证）；无 `Inbox` 保持原行为并 WARN 明示 "NOT deduplicated"。
- messaging-kafka 的 `RoutingOutboxDispatcher` 本地腿同样接线（LOCAL 事件与 externalized
  事件各走一腿、永不共键，无双记风险）。

红在先：starter 新测试修复前编译失败（构造器不存在）；实现后
`aRedeliveredMessageIsNotPublishedAgainWhenAnInboxIsPresent`（同 id 两次 dispatch 只发布一次）
与 `theInboxCheckAndThePublishShareOneTransaction`（发布发生在 dedup 事务回调内）为回归守卫。
outbox-starter + messaging-kafka 全模块绿；scaffold `start -am` 验收套件在新库下全绿。

## 关联

- 消费端幂等的另一半（业务级）：[issue-00130-two-of-three-effect-handlers-forgot-the-redelivery](issue-00130-two-of-three-effect-handlers-forgot-the-redelivery.md)
- 极晚重复的库存滞留：[issue-00147-a-very-late-duplicate-strands-a-reservation](issue-00147-a-very-late-duplicate-strands-a-reservation.md)
