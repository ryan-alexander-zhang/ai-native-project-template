---
id: issue-00097-the-payment-operation-log-has-no-cleanup
type: issue
role: main
status: open
parent: report-00002-scaffold-ddd-review
---

# `payment_operations` 无界增长：框架的每张同类表都有 cleanup，唯独这张新表没有

## 问题（现状，file:line 为证）

- **等级：Low（增长缓慢且不影响正确性；但它是 scaffold 新引入的第一张消费者侧幂等表，
  而"幂等日志需要保留期"恰恰是最容易被漏掉、且漏掉之后最难补的一件事）**。
- 本 issue **不是**评审时发现的，而是修
  [[issue-00069-payment-idempotency-claim-is-outside-the-transaction]] 时新引入的缺口：
  该修复把 `PaymentOperations` 从 `ConcurrentHashMap` 换成了
  `payment_operations` 表（`V6__payment_operations.sql`），
  **而没有配套的清理机制**。内存实现随进程重启自然清空，表不会。
- 对比框架自己的同类表，三张都有保留期，且 scaffold 都显式配置过
  （`application.yml` 的 `aipersimmon.ddd.outbox.cleanup` / `inbox.cleanup`）：

  | 表 | 清理 | 保留期 |
  |---|---|---|
  | `aipersimmon_outbox` | `outbox.cleanup.enabled=true` | 7 天 |
  | `aipersimmon_inbox` | `inbox.cleanup.enabled=true` | 30 天 |
  | `aipersimmon_operation_log` | 组件自带 | — |
  | **`payment_operations`** | **无** | **无** |

- `application.yml` 里 inbox 那段注释（"Retention must exceed the longest redelivery a broker can
  produce, or a very late duplicate is processed a second time — the one thing the inbox exists to
  prevent"）**逐字适用于这张表**：`payment_operations` 防的正是同一件事，
  只是键是业务的 `paymentOperationId` 而非传输层 event id。
  也就是说，正确的保留期判据已经写在同一个文件里了，只是没有被应用到这张表。

## 根因（第一性）

1. **观察 vs 期望**：期望"每一张只增写的技术表都有一条保留期决策"；
   实际"新加的这张表没有人问过这个问题"。
2. **最小机制**：`ConcurrentHashMap` → 表，替换的是**存储**；
   而"进程重启即清空"这条**隐含的保留策略**随之消失，且它从未被写下来过，
   所以替换时没有东西提醒它需要一个替代品。
3. **真根因**：issue-00069 的正确性论证聚焦在"认领与副作用同事务"这一条属性上，
   并且论证得很充分——但一次存储替换会同时改变**多个**属性
   （持久性、事务性、**生命周期**），而只有被论证的那一个被想过。
   隐含策略在被替换掉的那一刻是不可见的：没有人写过"这个 map 会在重启时清空"，
   因为那不是选择，是副作用。
4. **排除的伪根因**：不是"表就该无限保留"——幂等日志的价值随时间衰减到零，
   一条三年前的 `paymentOperationId` 不可能再被重投递。
   也不是"数据量不大"——增长率等于订单量，与业务同阶。

## 复现（test-first）

无法用行为测试复现（增长本身不是错误）。用一条结构断言，与
[[issue-00072-demo-seed-data-ships-in-a-production-migration]] 的
`MigrationContentTest` 同一手法：

```java
@Test
void everyAppendOnlyTableHasARetentionDecision() {
  // 已知需要保留期的消费者侧表，每一张都必须在 application.yml 里能找到 cleanup 配置，
  // 或在此列出显式豁免并写明理由。
  assertTrue(retentionConfigured("payment_operations"),
      "只增写的幂等日志必须有保留期；没有决策也算一种决策，但必须是显式的");
}
```

这条断言的价值不在这张表，而在**下一张**：它把"新增只增写表时要决定保留期"变成结构约束。

## 修复

三选一：

1. **加一个清理任务**（与框架一致）：一个 ShedLock 保护的定时任务删除
   `recorded_at < now() - retention` 的行，保留期取"broker 最长重投递窗口 + 安全余量"，
   与 inbox 的 30 天同源。放 `payment-infrastructure`。
2. **让它成为框架能力**：`PaymentOperations` 这类"业务幂等日志"是通用模式，
   `aipersimmon-ddd` 侧提供一个带 cleanup 的 `BusinessOperationLog` 组件，
   scaffold 只做配置。代价大，但避免每个消费者各写一遍清理。
3. **显式豁免并写明理由**：若认为这张表可以永久保留（审计价值），
   就在 `V6` 与端口 javadoc 里写清楚"这是有意的，因为 X"，
   并从"待办"变成"已决策"。**当前状态是三者中最差的：既没清理，也没说不清理。**

无论选哪条，都应同时补上复现段那条结构断言——本 issue 真正的产出是那条护栏，
而不是这一张表的保留期。

## 验证结果

未修。本 issue 由 [[issue-00069-payment-idempotency-claim-is-outside-the-transaction]] 的实施暴露。

## 关联

- [[issue-00069-payment-idempotency-claim-is-outside-the-transaction]]（这张表的由来）
- [[report-00002-scaffold-ddd-review]]
- [[issue-00072-demo-seed-data-ships-in-a-production-migration]]（同一类结构断言护栏）
