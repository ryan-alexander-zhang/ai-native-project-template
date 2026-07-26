---
id: issue-00059-outbox-relay-tests-race-the-startup-poll
type: issue
role: main
status: open
parent: report-00001-ddd-framework-review
---

# outbox relay 测试与「启动时的第一次轮询」抢 ShedLock 锁：间歇性失败，`poll-delay-ms` 挡不住

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

## 修复方向（未实施）

要把「关掉调度器」表达成结构，而不是一个大到不会触发的间隔：

- **首选**：让测试上下文不装配调度。`@EnableScheduling` 目前挂在 autoconfig 上，测试无法用属性关掉它——
  这本身是可用性问题（消费方也无法在集成测试里关掉框架的后台轮询）。建议给框架加一个
  `aipersimmon.ddd.outbox.relay.enabled`（默认 `true`），测试置 `false`；这与 process-manager
  已有的 `effect-relay.enabled` / `deadline-worker.enabled` 形状一致，**属于补齐既有约定，而非新发明**。
- 备选（纯测试侧）：测试不注入被 `@SchedulerLock` 包裹的 bean，而是直接构造 `OutboxRelay` 调用其逻辑，
  绕开 ShedLock 切面。缺点是不再覆盖真实装配路径。

**不采用**「重跑即可」或「把断言放宽成 `>= 0`」——那会把一个真实的竞态改写成永真断言。

## 影响与当前状态

- 产品行为**不受影响**：`@Scheduled` 启动即跑一次是生产上想要的（服务重启后立即排空积压），
  ShedLock 跳过重复轮询也是正确的。**只有测试对调度器的假设是错的。**
- 本 issue **未修复**，故标记 `open`。它会让全量构建偶发变红；遇到时可确认失败是否正是本条，
  而不是把它当作新回归。

## 关联

- [[report-00001-ddd-framework-review]]
- [[plan-00014-adoption-threshold-and-architecture-simplification]]（在 D 批实施过程中发现）
- [[issue-00056-kafka-tests-pin-a-stale-inbox-schema]]（同类：测试夹具对框架行为的假设与实际不符）
