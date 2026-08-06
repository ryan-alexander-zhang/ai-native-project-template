---
id: issue-00103-parked-input-replay-is-not-crash-safe
type: issue
status: resolved
blocks: [report-00003-ddd-library-review-2026-07-29]
---

# 挂起期间 park 的输入，重放只活在 redrive 的调用栈里：崩溃即永久丢失，且全树无人扫描

## 问题（现状，file:line 为证）

- **等级：Critical**（报告 §1 C4）。丢的是已被 broker ack 的业务输入，且无告警、无痕迹。
- `ProcessOperations.redriveEffect` / `redriveDeadline`（旧 `ProcessOperations.java:75-117,120-169`）
  的形状是：**先提交** resume 事务，**再**在事务之外调 `replayParkedInputs(resumed)`：

```java
ProcessRef resumed = unitOfWork.execute(() -> { ... instances.resume(...); return row.ref(); });
if (resumed != null) {
  replayParkedInputs(resumed);   // 提交之后，任何事务之外
}
```

- `replayParkedInputs`（旧 `ProcessOperations.java:180-200`）遍历 `findParkedInputs`，逐条
  `runtime.handle(...)`。注释写着「`handle()` never throws，所以剩下的输入会留到下次 redrive」——
  但 `handle` 实际可抛四类异常：`ProcessNotFoundException`、`UnsupportedProcessInputException`、
  `ProcessSerializationException`，以及 Definition 自己抛的任何 `RuntimeException`。
- 于是 resume 已提交、实例已是 RUNNING，而重放在中途失败或进程被 `kill -9`：
  - parked 行还在，但**再没有任何入口会去看它**——全树只有 `replayParkedInputs` 一个调用点，
    而它只能由操作员再发一次 redrive 触发；实例已经不是 SUSPENDED，也就没有 redrive 的对象了。
  - `countStuck` 要求「无 pending effect/deadline」，重放丢失的实例通常仍有 effect，
    连卡死扫描也捞不到。
- 结论：**「一次重放的债务」这件事只存在于一次 HTTP 调用的调用栈里**。栈没了，债务就没了。

## 根因（第一性）

1. **观察 vs 期望**：期望「park 的输入终将被交回 Definition 恰好一次」；
   实际「若重放这一步没能跑完，输入永久停在 parked 状态，且系统认为一切正常」。
2. **最小机制**：park 是**持久的**（一行 transition），重放却是**易失的**（一次方法调用）。
   持久状态与它的待办之间没有落盘的对应关系：`transition_kind = 'PARKED'` 只说「它曾被 park」，
   不说「它还欠一次重放」。缺的正是**处置标记**。
3. **同族对照**：框架对 effect 与 deadline 早就做对了——它们是带 status/lease/attempts 的持久工作行，
   由带租约的 worker 轮询驱动，崩溃后自愈。parked 输入是**第三种待办**，却唯一没有 worker。
4. **排除的伪根因**：不是「重放不幂等」（`UNIQUE(instance_id, 'parked:'+id)` 已经保证了幂等）；
   问题不在重复，而在**遗漏**。

## 复现（test-first）

`JdbcProcessOperationsTest.redriveResumesAndLeavesTheReplayOwedUntilTheWorkerDrainsIt`
与 `aReplayThatAlreadyCommittedIsNotAppliedTwice`：

- 前者断言 redrive 之后 step 仍是 `S1`、队列仍欠 1 条——即 redrive 只提交 resume，
  此刻崩溃不丢任何东西；随后 worker 一次 poll 推进到 `S2` 并结清债务。
- 后者模拟「重放已提交、标记未写」的崩溃窗口（把 `replayed_at` 置回 NULL），再 poll：
  state 仍为 `S2|1`，即重复重放是 no-op，标记最终被补上。

## 修复

**把重放从「操作员调用栈里的一步」改成「一条持久队列 + 一个 worker」。**

1. **持久队列**：迁移 V4 给 `aipersimmon_process_transition` 加 `replayed_at`（三方言）。
   `replayed_at IS NULL` 的 PARKED 行就是「还欠一次重放」。它是 transition 行**唯一**
   在插入后被写过的字段，记录的是那条输入的**处置**，不是它所记录的决策——
   append-only 的实质约束因此没有破，但这一点在端口 javadoc 与 DDL 注释里被明说了。
2. **worker**：新增 `aipersimmon-ddd-process-manager-engine` 的
   `engine.replay.ParkedInputWorker`，与 relay / deadline worker 并列挂在
   `ProcessWorkerScheduler` 上（第三条单线程腿）。它扫「活跃且仍欠重放」的实例，
   按 `transition_seq` 顺序逐条交回 `runtime.handle`，**每条的 advance 提交后**才写标记。
3. **redrive 只做 resume**：`ProcessOperations` 不再持有 `ProcessRuntime` 与
   `ProcessPayloadCodecRegistry`，两个 redrive 各自回归单事务。
4. **顺序在并发下仍然成立且不需要租约**：两个节点同时排空同一实例是安全的——
   每条重放由 replay transition 的唯一键去重，而任一节点都不可能在第 k 条的 replay 提交前
   碰到第 k+1 条（它必须先走第 k 条，那次 `handle` 会阻塞在实例行锁上）。
   故「按到达顺序」在重叠下依然成立，省掉了一层租约。
5. **毒输入不再热重试**：重放抛异常时把实例挂起为 `suspensionSource=PARKED_INPUT`
   （与 effect/deadline 耗尽重试同一形状），它随即退出 worker 的候选集、进入挂起 SLI，
   等操作员 redrive。否则每个 poll 都会重试一条永远失败的输入。
6. **重放不会被再次 park**：新增 `ParkedInputs`（`REPLAY_PREFIX` 的唯一定义处）。
   若实例在重放落地前又被挂起，runtime 认出这是一条重放、**不**插入新的 parked 行，
   而是返回实例的 SUSPENDED 生命周期——worker 据此把债务留在原处并停止排空该实例。
   旧代码会为它再插一行，于是 `parked:parked:…` 前缀链每轮增长 7 字符，
   最终撑爆 `input_message_id VARCHAR(96)`。
7. **schema 校验升级为列级**：两个 `*SchemaValidator` 的探针改为 `SELECT tenant_id, replayed_at`
   而不是 `SELECT 1`——「表存在」不等于「schema 是当前的」，而少了 `replayed_at`
   的后果恰恰是重放在后台线程上每个 poll 失败一次，最难被发现。

**未做的选择及原因**：没有为 parked 输入单开第 5 张表（队列会与审计行产生双写一致性问题，
而 parked 行本身已经是队列）；没有给 worker 加租约（幂等 + 队头顺序已经足够，租约只会
增加一处可失效的状态）；没有把重放塞进 resume 的同一个事务（一条输入失败会连带回滚 resume，
且 `handle` 会因此变成 joined 事务，撞上 [[issue-00105-an-advance-conflict-inside-a-joined-transaction-cannot-be-retried]]）。

## 验证结果

- 库全量 `mvn verify`（47 模块，含 Testcontainers PG/MySQL、spotless/PMD/CPD/SpotBugs/JaCoCo/PIT）：BUILD SUCCESS。
- 脚手架 `multi-module` 全量 `mvn verify`：BUILD SUCCESS（V4 随 engine jar 的 classpath 资源被 Flyway 自动应用）。
- 新增/改写的测试：`JdbcProcessOperationsTest` 5 条与重放相关的用例（含崩溃窗口、毒输入、
  挂起期间不重放、到达顺序），`JdbcProcessTransitionStoreTest.aReplayedParkedInputLeavesTheQueueAndIsMarkedOnlyOnce`。

## 关联

- 父：[[report-00003-ddd-library-review-2026-07-29]]（§1 C4）
- 设计：[[design-00004-durable-process-manager-runtime]] §4.6 / §4.7 / §4.10 已同步
- 同批：[[issue-00104-an-ended-instance-keeps-its-timers-forever]]、
  [[issue-00105-an-advance-conflict-inside-a-joined-transaction-cannot-be-retried]]
- 先例：[[issue-00037-parked-input-replay-order-non-monotonic]]（重放顺序改用 `transition_seq`，本次沿用）
