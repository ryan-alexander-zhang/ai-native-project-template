---
id: issue-00059-outbox-relay-tests-race-the-startup-poll
type: issue
status: resolved
blocks: [report-00001-ddd-framework-review]
---

# outbox relay 测试与「启动时的第一次轮询」抢 ShedLock 锁：间歇性失败，`poll-delay-ms` 挡不住

> **注（2026-08-06 补）**：本记录写于库同时并存 JDBC 与 MyBatis-Plus 两套存储后端的时期。
> `-persistence-jdbc`、`-outbox-jdbc`、`-inbox-jdbc`、`-process-manager-jdbc`、`-operation-log-jdbc`、
> `-web-store-jdbc`、`-starter-jdbc` 已全部删除（库只留 MyBatis-Plus 后端；web 边界存储由
> `-web-store-mybatis-plus` 承接）。因此下文带 `-jdbc` 的模块名、路径与 `file:line`，指的是当时的代码，
> 不是现在的树；它们作为当时的证据保留，未被改写成 MyBatis-Plus 的路径。

## 问题（现状，file:line 为证）

- **等级：Medium（测试基础设施缺陷，不是产品缺陷。但它让 outbox 两个后端的构建间歇性变红，
  而间歇性红是最容易被「重跑一次就好了」掩盖掉的一类信号）**。
- 现象：全反应堆构建中偶发
  ```
  OutboxMybatisPlusTest.writesUnsentRowThenRelayDispatchesAndMarksSent:81 expected: <1> but was: <0>
  ```
  同一模块**单独跑必过**（`-pl aipersimmon-ddd-outbox-mybatis-plus` 全 13 项绿），
  同一命令重跑也可能过——三次全量构建里失败 2 次、通过 1 次。
- 相关代码：
  - `outbox-mybatis-plus/AipersimmonDddOutboxMybatisPlusAutoConfiguration.java:57` —— `@EnableScheduling`
  - `outbox-mybatis-plus/OutboxRelay.java:102-107` —— `@Scheduled(fixedDelayString = "${aipersimmon.ddd.outbox.poll-delay-ms:1000}")`
    ＋ `@SchedulerLock(name = "...-outbox-relay", lockAtMostFor = "PT60M")`
  - `OutboxMybatisPlusTest.java:32-33` —— 用 `aipersimmon.ddd.outbox.poll-delay-ms=3600000` 试图让后台调度器
    在测试期间不触发，注释明确写着 "The poll delay is set very high so the background scheduler does not fire
    during the test; the relay is invoked directly instead."
- 同一手法出现在两个后端共 **7 个测试类**（`-outbox-jdbc` 与 `-outbox-mybatis-plus` 各若干），
  即失效面不止一个测试。

## 根因（第一性）

1. **观察 vs 期望**：期望「把 `poll-delay-ms` 调到 1 小时 ⇒ 后台轮询在测试期间不会跑」；
   实际「后台轮询在**上下文启动时立即跑了一次**」。
2. **最小机制**：Spring 的 `@Scheduled(fixedDelay = D)` 语义是**先执行、再等 D**，不是「先等 D」。
   把 D 调大只推迟**第二次**轮询，对**第一次**毫无作用。于是每个测试上下文启动后都会立刻发生一次真实的
   `relay()`。
3. **为什么会失败而不只是多跑一次**：`relay()` 上还有 `@SchedulerLock`。ShedLock 的切面在**拿不到锁时直接跳过
   方法体**。当启动轮询恰好还持有锁时，测试体里那次「直接调用 `relay()`」被整个跳过——不抛异常、不报警，
   只是什么都没做，于是 `CapturingDispatcher` 里是 0 条。
4. **为什么间歇**：启动轮询在调度线程上，测试体在主线程上。二者是否重叠取决于「启动轮询扫一张空表并释放锁」
   与「测试写入行并调用 relay」的相对时序——机器负载、反应堆里前面模块留下的 JIT/GC 状态都会影响它。
   这解释了「单模块跑必过、全量构建偶发失败」。
5. **真根因**：测试用**一个时间参数**去表达「关掉调度器」这个**结构**意图。`poll-delay-ms` 控制的是节奏，
   不是开关；用它来关调度，在语义上就不可能可靠。
6. **排除的伪根因**：
   - 不是 H2 状态跨测试泄漏——单模块内 13 项一起跑同样全绿。
   - 不是 `-outbox` 拆分（本轮改动）引入的。拆分只移动了类，`@EnableScheduling` 与 `fixedDelay` 语义在此之前
     就是这样；拆分后首次出现只是因为它改变了构建时序，让这个既有竞态更容易被撞上。
   - 不是 `target/` 残留。已确认 `-outbox/target/classes` 干净，且无残留 `AutoConfiguration.imports`。

## 复现

不稳定复现（约 2/3）：连续跑全反应堆增量构建
`mvn -f aipersimmon-ddd/pom.xml install -Djacoco.skip=true`。

确定性复现思路（尚未实现）：在测试上下文里让启动轮询持锁一段时间（例如注入一个 `dispatch` 里 sleep 的
`OutboxDispatcher`），再从测试体调用 `relay()`，断言它被 ShedLock 跳过——这会把间歇性竞态变成必然失败，
是修复前应当先写的那个测试。

## 修复（已实施）

采纳第一节所列的**首选**方案：把「被调度的触发器」从 `OutboxRelay` 上分出来。

- 新增 `OutboxRelayScheduler`（两个后端各一个，约 30 行）。`@Scheduled` 与 `@SchedulerLock` **移到它的
  `poll()` 上**，`poll()` 只做一件事：调 `relay.relay()`。
- `OutboxRelay.relay()` 不再带任何注解，因此**直接调用不会被任何锁静默跳过**——这消掉了竞态的后一半。
- 新属性 `aipersimmon.ddd.outbox.relay.enabled`（默认 `true`）只控制 **scheduler bean 是否装配**，
  relay bean 不受影响。这消掉了竞态的前一半（启动即轮询）。与 process-manager 已有的
  `effect-relay.enabled` / `deadline-worker.enabled` 同形，是补齐既有约定。

**锁为什么留在 scheduler 而不是 relay**：它守护的是**调度**——防止多实例同时轮询同一批行。
直接调用是单个调用方的明确动作，不需要这个守护，更不该被它静默否决。

16 个测试类从「把 `poll-delay-ms` 调到 1 小时」改为 `relay.enabled=false`，即从「调得足够慢」改为
「结构上关掉」。两个 `OutboxTracingTest` 里那条「给本测试起唯一锁名」的绕法一并删除——它只解决跨测试类的
锁争用，不解决本上下文的启动轮询，现在有了正解就不该留两套机制。

`relayPollIsGuardedByShedLock` 这条既有断言**没有被删掉，而是搬了家**：它守护的契约（调度轮询必须持锁）
依然成立，只是方法换了位置，因此迁到新的 `OutboxRelayScheduleTest`（两个后端各一份，调 `scheduler.poll()`）。
**留在原处会必然失败——那正是本次改动应当被察觉的地方。**

## 验证结果

新增 3 个测试类：

- `OutboxRelayScheduleTest`（jdbc + mybatis-plus）—— 默认装配 scheduler，且 `poll()` 确实取到 shedlock 行。
- `OutboxRelayScheduleDisabledTest`（jdbc）—— `relay.enabled=false` 时 scheduler bean **不存在**、
  relay bean **仍存在**，且直接 `relay()` 后 shedlock 表里 **0 行**——即直接路径已无锁可跳过。

**间歇性已消除**：两个 outbox 模块连跑 **3 次**全绿（此前三次全量构建里红 2 次）。
框架全量 `install`（全质量门）通过；样例 `verify` BUILD SUCCESS。

`CONFIGURATION.md` 补上 `relay.enabled`，并把 `poll-delay-ms` 的说明改为「控制**第一次之后**的节奏」——
文档此前也隐含了同一个错误假设。

## 关联

- [report-00001-ddd-framework-review](../report/report-00001-ddd-framework-review.md)
- [plan-00014-adoption-threshold-and-architecture-simplification](../plan/plan-00014-adoption-threshold-and-architecture-simplification.md)（在 D 批实施过程中发现）
- [issue-00056-kafka-tests-pin-a-stale-inbox-schema](issue-00056-kafka-tests-pin-a-stale-inbox-schema.md)（同类：测试夹具对框架行为的假设与实际不符）
