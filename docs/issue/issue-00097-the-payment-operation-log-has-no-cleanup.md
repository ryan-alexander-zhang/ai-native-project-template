---
id: issue-00097-the-payment-operation-log-has-no-cleanup
type: issue
status: resolved
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

已修。选了**修复方案 1（加清理任务，与框架一致）**，并补上了那条护栏。方案 2（上提成框架组件）
成本过高且只有一个使用者；方案 3（显式豁免）不成立——保留期判据已经在同一个文件里了。

**实际改动，有两处与 issue 原稿不同**：

1. **不用 ShedLock**。原稿写"一个 ShedLock 保护的定时任务"。但去读框架自己的
   `InboxCleanup` / `OutboxCleanup`，它们**都没有加锁**，且注明了理由：
   删除是一条以 cutoff 为界的单语句，多实例同时跑是冗余但无害（幂等）。
   照抄框架的做法比照抄 issue 的提议更对，`PaymentOperationCleanup` 的 javadoc 逐字记了这个理由。
2. **bean 注册在组合根，不在 adapter 上**。`PaymentOperationCleanup` 是
   `payment-infrastructure` 里一个不带 Spring 注解的普通类；
   `start` 的 `PaymentOperationCleanupConfig` 用 `@ConditionalOnProperty` 决定要不要装配它。
   这与框架"类在 store 模块、装配在 auto-config"的分法一致，
   而且说的是同一件事：**要不要删数据、留多久，是部署决策，不是这个适配器的属性**。

**保留期取 30 天，与 inbox 同源**——不是各自选的数字。`application.yml` 里两处互相指：
inbox 那段末尾加了一行指向文件底部的 `payment:` 块，`payment:` 块说明为什么判据是同一条
（都是"一条被清掉的键就不再被识别为重复"，所以都必须长于 broker 最长重投递窗口）。

**产出的护栏是 `TableRetentionTest`，它才是本 issue 的重点**：

- 从 `db/migration` 里**扫出** `CREATE TABLE`，逐张要求在 `DECISIONS` 里有条目——
  所以**新加一张表就会红**，直到有人写下它属于哪一类。
- 两类都是合法答案：`purgedAfter("<property>")`（并且该 property 必须在 `application.yml` 里真的存在）
  或 `keptForever("<理由>")`。当前 7 张表里 6 张是后者（订单、库存都是业务数据），
  只有 `payment_operations` 是前者。**"没写"才是这条护栏要挡的状态。**
- 范围只限本应用自己的迁移；框架的 outbox/inbox/operation-log/process-manager 四类表由组件迁移建、
  在 `aipersimmon.ddd.*` 下配置，不归这里管。javadoc 写明了。

**负向对照（两条都实测过）**：

```
# A. 新增一张没有决策的表（临时 V8__control.sql）
these tables have no retention decision: [payment_attempts]. Add one to DECISIONS. ...

# B. 把 retention-seconds 改名，让决策指向一个不存在的 property
payment_operations is documented as purged after payment.operations.cleanup.retention-seconds,
but no such property is set in application.yml — the decision exists only in this test,
and the rows would grow forever
```

第二条尤其重要：没有它，`DECISIONS` 里写一句 `purgedAfter(...)` 就能让第一条断言变绿，
而实际什么都没配——**护栏本身会变成那种"写下来就算数"的假绿**。

另有 `PaymentOperationCleanupTest`（`payment-infrastructure` 的第一个单测）钉住 cutoff 的**符号**：
窗口方向搞反会删掉所有近期操作、留下已过期的，恰好是 dedupe 日志需要的反面，
且症状是重复授权而不是报错。为此给该模块加了 `junit-jupiter`（test）与
`slf4j-api`（清理任务要报告删了多少行——一个删数据却什么都不说的定时任务，
坏掉一个月也不会有人发现）。

**一个后续**：本修复给 mapper 加的方法最初叫 `deleteRecordedBefore`，
这个名字让 SpotBugs 把整个 mapper 接口判为可变类型，进而把**所有持有它的类**判红——
包括本次没改过的 `MyBatisPaymentOperations`。`mvn verify` 因此在 `f350e03` 之后一度是红的。
见 [[issue-00098-a-mapper-method-name-turns-spotbugs-against-its-callers]]，已改名 `purgeRecordedBefore` 修复。

验证：`mvn -o test -pl payment/payment-infrastructure -am` 绿；
`mvn -o test -pl start -am -Dtest=ApplicationSmokeTest,PaymentOperationAtomicityTest,TableRetentionTest` 5 条全绿
（应用带着新 bean 正常启动，`@Value` 解析到位）。

## 关联

- [[issue-00069-payment-idempotency-claim-is-outside-the-transaction]]（这张表的由来）
- [[report-00002-scaffold-ddd-review]]
- [[issue-00072-demo-seed-data-ships-in-a-production-migration]]（同一类结构断言护栏）
