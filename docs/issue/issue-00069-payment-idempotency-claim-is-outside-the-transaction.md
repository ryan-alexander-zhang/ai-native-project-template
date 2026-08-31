---
id: issue-00069-payment-idempotency-claim-is-outside-the-transaction
type: issue
status: resolved
blocks: [report-00002-scaffold-ddd-review]
---

# 演示的业务幂等模式有回滚洞：认领在事务外，且重投递静默返回而不重发结果

## 问题（现状，file:line 为证）

- **等级：High（不是因为它现在会出错——payment deadline 会兜住——而是因为这是 scaffold 给出的"不可逆动作如何幂等"的**唯一**范例，会被原样抄进真实的支付、扣款、发货代码）**。
- `AuthorizePaymentHandler.handle`（`AuthorizePaymentHandler.java:40-53`）：

```java
PaymentDecision decision = authorization.decide(...);
if (!operations.recordIfFirst(command.paymentOperationId(), decision)) {
  return null;                                    // 重投递：静默返回
}
switch (decision) { ... integrationEvents.publish(...); }
```

两个独立缺陷：

**（1）认领不在事务里。** `InMemoryPaymentOperations` 是一个 `ConcurrentHashMap`
（`InMemoryPaymentOperations.java:29`），`putIfAbsent` 一旦返回就**不可撤销**。
若认领成功之后当前事务回滚（outbox 写入失败、后续拦截器抛异常、连接中断），
map 里的认领留下了、outbox 行没了。此后**任何重投递都会走 `return null`**，
outcome 事件永久不再发出。

`payment/pom.xml:16-20` 与 `PaymentOperations.java:18-22` 把这件事描述为
"in-memory 是持久化 operations 表的轻量替身"——**这个描述低估了差异**。
让这个模式成立的关键属性不是"持久"，而是"**认领与发布在同一个事务里，一起成功或一起回滚**"。
换成一张普通的表、但用独立事务写，洞一模一样。

**（2）重投递应重发已记录的结果，而不是静默返回。** 至少一次投递的前提是"上一次的
outcome 事件可能没送达"，所以重投递的正确响应是**重发上次记录的那个决定**（幂等地），
而不是假设对方已经收到。

证据是代码自己留下的：`PaymentOperations.find(operationId)`
（`PaymentOperations.java:35`，返回"the decision recorded for operationId"）
**定义了、实现了、全仓零调用**（`grep -rn "\.find(" payment/` 无命中）。
作者显然知道正确形状，只是没接上最后一根线。

## 根因（第一性）

1. **观察 vs 期望**：期望"至少一次投递 ⇒ 恰好一次授权 + 恰好一次 outcome 送达"；
   实际"恰好一次授权 + **至多**一次 outcome 送达"。
2. **最小机制**：认领的持久化边界（`ConcurrentHashMap`，进程内、无事务）与
   副作用的持久化边界（`aipersimmon_outbox`，DB 事务内）**不是同一个边界**。
   两个边界不一致时，中间任何一次失败都会让二者对这次操作的记忆不同。
3. **真根因**：把"幂等键存在哪"当成了实现细节（"in-memory 够演示了"），
   而它其实是**这个模式的正确性前提**。
   `AuthorizePaymentHandler` 的注释（`:18-24`）把 `recordIfFirst` 描述为"the atomic claim"——
   它相对于并发确实是原子的（`putIfAbsent`），但相对于**事务**不是。
   注释里的 "atomic" 指的是前者，读者会理解成后者。
4. **排除的伪根因**：不是"in-memory 会丢数据"——重启丢失只会让它退化成重复授权，
   与本 issue 描述的静默丢失方向相反；真正的问题是**认领在事务外存活**。
   也不是 outbox 不可靠——outbox 一旦提交就保证送达；洞在提交之前。

## 复现（test-first）

`AuthorizePaymentIdempotencyTest` 目前只覆盖"重投递不重复授权"。补两条：

```java
@Test
void aClaimDoesNotSurviveARolledBackTransaction() {
  // 拦截器在 handler 之后、事务内抛异常（照抄 OutboxAtomicityTest.FailInsideTransaction 的形状）
  assertThrows(RuntimeException.class, () -> commandBus.send(authorize("op-1")));
  // 当前：认领已留在 map 里 —— 下面这条会失败
  commandBus.send(authorize("op-1"));
  assertEquals(1, outboxRowsFor("op-1"), "回滚后的重投递必须能重新授权并发出 outcome");
}

@Test
void aRedeliveryRepublishesTheRecordedOutcome() {
  commandBus.send(authorize("op-2"));
  long before = outboxRowsFor("op-2");
  commandBus.send(authorize("op-2"));            // 重投递
  assertEquals(before + 1, outboxRowsFor("op-2"),
      "重投递必须重发已记录的结果——上一次的 outcome 可能从未送达");
}
```

第二条需要先确认下游对重复 outcome 是幂等的：`OrderFulfilmentDefinition` 的
`(step, input)` 分派对重复 `PaymentAuthorized` 走 `ignore`（`:274`），所以是安全的。

## 修复

1. **把 `PaymentOperations` 换成同 DataSource 的表**，写入走命令事务
   （`payment_operations(operation_id PK, decision, recorded_at, tenant_id)`，
   靠主键约束做认领）。这样认领与 outbox 行同生共死。
   `payment` 上下文因此会获得它的第一张表——这不违背"payment 不拥有持久化聚合"的设计，
   幂等日志本来就是技术性出站适配器（`payment/pom.xml:17-20` 已经这么定位它了）。
2. **重投递改为重发**：

```java
Optional<PaymentDecision> recorded = operations.find(command.paymentOperationId());
PaymentDecision decision = recorded.orElseGet(() -> authorization.decide(...));
if (recorded.isEmpty()) { operations.record(command.paymentOperationId(), decision); }
publish(decision, command, context);            // 首次与重投递走同一条出口
```

   这条同时消灭了 `find()` 这个死方法（见 [issue-00082-domain-surface-no-use-case-can-reach](issue-00082-domain-surface-no-use-case-can-reach.md)）。
3. **改掉注释**：`PaymentOperations` 与 `payment/pom.xml` 里"in-memory 只是轻量替身"的说法要写清楚——
   真正的要求是**与副作用同事务**，"持久化"只是它的必要条件之一。

## 验证结果

已修。三条修复全做。

- **（1）认领进事务**：`InMemoryPaymentOperations` 删除，改为 `MyBatisPaymentOperations` +
  `PaymentOperationMapper` 两条语句，落在 `V6__payment_operations.sql` 的
  `payment_operations(tenant_id, operation_id, outcome, decline_code, decline_reason, recorded_at)`，
  主键 `(tenant_id, operation_id)`。mapper 走命令的 `SqlSession`，与 outbox 同事务同生共死。
  payment 因此获得第一张表——如本 issue 所述，这不违背"payment 不拥有持久化聚合"，
  幂等日志本来就是技术性出站适配器。
- **（2）重投递改重发**：`AuthorizePaymentHandler` 改成本 issue 给的形状
  （`find` → `orElseGet(decide)` → 仅首次 `record` → **首投与重投走同一条出口**）。
  `find()` 从此不再是死方法（[issue-00082-domain-surface-no-use-case-can-reach](issue-00082-domain-surface-no-use-case-can-reach.md) 的一处随之消除）。
  重发安全性按本 issue 的要求先确认过：`OrderFulfilmentDefinition` 的 `(step, input)` 分派
  对重复 `PaymentAuthorized` 走 `ignore`。
- **（3）注释改对**：`PaymentOperations`、`MyBatisPaymentOperations`、`payment/pom.xml`、
  `V6` 全部改写为——**要的不是"持久"，是"与副作用同事务"**，持久只是必要条件之一。
  端口的 javadoc 还解释了为什么拆成 `find` + `record` 而不保留 `recordIfFirst`：
  布尔只能回答"我能继续吗"，这个形状会把调用方推向"重投递静默返回"，而静默正是错的。

**端口签名换了，不只是实现换了**：`recordIfFirst(id, decision) -> boolean` 删除，
改为 `find(id)` + `record(id, decision)`（重复即抛）。并发不再靠 check-then-act：
两个首投都 `find` 到空、都 `decide`、输家 `INSERT` 撞主键 → 回滚 → 重试时 `find` 到赢家的决定并重发。
**约束本身就是认领**，中间没有窗口。

**决定性的负向对照**（正是本 issue 论点的要害）：把实现换成一张**真表、但用
`PROPAGATION_REQUIRES_NEW` 写**，`aClaimDoesNotSurviveARolledBackTransaction` 立刻红——
`the claim must roll back ... expected: <0> but was: <1>`。
**持久化没有修好任何东西**，认领照样活过了回滚。这条对照比"换回 ConcurrentHashMap"更有说服力，
后者因为根本不写表，会以错误的理由失败。

测试：
- `AuthorizePaymentIdempotencyTest`（单元，4 条）**原有两条断言被改了**——
  它们此前钉的是"重投递只发一个事件"，也就是本 issue 要修掉的行为。
  现在断言"授权一次、事件两次"，并新增一条：输掉并发认领的一方抛异常且不发布任何东西。
- `PaymentOperationAtomicityTest`（端到端，2 条）按本 issue 复现段落地，
  事务内拦截器照抄 `OutboxAtomicityTest.FailInsideTransaction` 的形状（改为可按测试武装）。

**未做**：`payment_operations` 没有清理任务。幂等日志会无界增长，
真实部署需要按保留期清理（框架的 outbox/inbox 都有 `cleanup`，这张表没有）。
不在本 issue 范围，值得单独落一条。

验证：`mvn -o verify -pl start -am` 全绿，73 个测试 0 失败，Spotless / PMD / SpotBugs 通过。
（SpotBugs 顺带影响了实现选择：持有注入的 `JdbcTemplate` 触发 `EI_EXPOSE_REP2`，
而共享豁免清单在库的 `aipersimmon-ddd-quality-config` 里、且只列库自己的类；
改用 `@Mapper` 接口既避开了这个问题，也与另外两个上下文的持久化写法一致。）

## 关联

- [report-00002-scaffold-ddd-review](../report/report-00002-scaffold-ddd-review.md)
- [design-00004-durable-process-manager-runtime](../design/design-00004-durable-process-manager-runtime.md)（§13.2 业务幂等键与传输层 effect id 的分工）
- [issue-00082-domain-surface-no-use-case-can-reach](issue-00082-domain-surface-no-use-case-can-reach.md)（`find()` 是其中一处死代码）
- [issue-00068-stock-waits-have-no-deadline-and-can-park-forever](issue-00068-stock-waits-have-no-deadline-and-can-park-forever.md)（payment deadline 目前是这个洞的唯一兜底）
