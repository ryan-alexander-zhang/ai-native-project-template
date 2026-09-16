---
id: issue-00174-a-published-process-effect-cannot-be-delivered-through-the-outbox
type: issue
status: resolved
blocks: [design-00004-durable-process-manager-runtime]
---

# Issue：`PublishIntegrationEvent` 效果永远投递不到 outbox——relay 线程上没有事务，`OutboxWriter` 拒绝写行

> 任何用默认装配（outbox 作 `IntegrationEvents`）的应用里，流程产出的每一条 `PublishIntegrationEvent` 效果都在每次尝试时失败、耗尽重试后进 `DEAD` 并挂起实例；流程发布的事实一条也到不了 outbox。

## 1. Problem

- Observed：效果行落库后 relay 每次投递都抛
  `java.lang.IllegalStateException: no active transaction while writing <event> to the outbox: the row must commit with the state change that caused it, ...`；
  `aipersimmon_process_effect` 行 `status=PENDING` 反复重试，最终 `DEAD`，实例 `SUSPENDED`（`suspension_source=EFFECT`）。
- Expected：效果经 outbox 写行、被 outbox relay 发出；效果行标 `DELIVERED`。依据：
  `PublishIntegrationEvent` javadoc（"publish {@code event} through the outbox"）、
  `design-00004` §4.6（"`IntegrationEventEffectDispatcher` → 经 outbox → broker 派发"）、§13（"relay 经 outbox → broker 发出"）。
- Trigger：任意部署同时满足——有 `OutboxStore`（outbox 引擎装配出 `OutboxWriter` 作为 `IntegrationEvents`）、
  未自定义 `IntegrationEvents` bean、流程 Definition 产出 `PublishIntegrationEvent`。这是 `design-00004` §3.5 描述的"目标 BC 是独立微服务"的标准形态。
  下游实践项目的 `RevocationCascadeProcess` 首次走这条通路即触发。

## 2. Impact

- Affected：所有用框架默认装配、且流程需要跨服务发布集成事件的应用。框架内没有一个 sample 走这条通路（s09 README 明言避开 `PublishIntegrationEvent`；s04 没有 process manager），所以库自身一直未察觉。
- Since：`44f722e0`（2026-07-29）· Still occurring：no（本 issue 修复）
- Severity：P1。功能性完全失效，不是降级：该效果类型 100% 失败；每条这样的效果耗尽重试后挂起整个流程实例，需要运维介入；
  且失败发生在 relay 线程，业务命令本身成功返回，调用方毫无感知。

## 3. Root Cause (first principles)

1. 期望：`IntegrationEventEffectDispatcher.dispatch` 调 `OutboxWriter.publishAs` 时线程上有活动事务。实际：没有。
2. 机制：
   - `ProcessEffectRelay.java:186` `dispatch(ClaimedEffect, leaseToken)` 在事务之外执行——javadoc 第 27 行"out of the advance transaction"是设计意图；
     注入的 `ProcessUnitOfWork` 只包 claim（`:153`）与 markDead+suspend（`:224`），不包 `dispatchers.dispatch`（`:199`）。
   - `IntegrationEventEffectDispatcher.java:32` 直接 `integrationEvents.publishAs(...)`，不加任何边界。
   - `OutboxWriter.java:129` `write()` 首行 `requireActiveTransaction(event)`，`TransactionSynchronizationManager.isActualTransactionActive()` 为假即抛（`:186-197`）。
   - `AipersimmonDddOutboxEngineAutoConfiguration.java:70-72` 在有 `OutboxStore` 且无 `IntegrationEvents` 时装配 `OutboxWriter`；
     `AipersimmonDddProcessManagerAutoConfiguration.java:272-278` 在有 `IntegrationEvents` 时装配 `IntegrationEventEffectDispatcher` 包住它。
     两个 `@ConditionalOnBean` 各自成立，合起来就是一对必然失败的组合。
3. 真因：**两个正确的局部契约从未在同一线程上对过账。** relay 有意不在事务里派发（命令通路不能 join 它的事务，见 §6）；
   `OutboxWriter` 有意要求事务（否则 outbox 的唯一卖点——行与状态变更同提交——不成立）。
   唯一同时接触两者的构件是 `IntegrationEventEffectDispatcher`，它没有为 outbox 写入提供边界。
   它**不是** `OutboxWriter` 检查过严（检查正确，`44f722e0` 的提交说明写清了为什么要检查）；
   也**不是** relay 该开事务（命令派发已由 `TransactionCommandInterceptor` 自带事务，`CommandEffectDispatcher` 因此能在同一 relay 线程上工作——这正是为什么只有事件通路坏）。
- Introduced by：`44f722e0 fix: check the guarantees that used to disappear quietly`（2026-07-29）给 `OutboxWriter` 加了 `requireActiveTransaction`。
  在此之前 relay 线程上的 outbox INSERT 自动提交、能成功（`issue-00032` 当时就记录了"outbox INSERT 自动提交"这一事实），缺陷无从发生。
  该提交修的是 outbox 一侧的沉默失效，但没有扫到它的唯一非事务调用方。

## 4. Scope (same-cause sweep)

机制 = "在 relay 线程（无事务）上调用一个要求活动事务的写端口"。`ProcessEffectDispatcher` 的全部实现：

| Site | Same pattern | Affected | Action |
| --- | --- | --- | --- |
| `relay/IntegrationEventEffectDispatcher.java:32` → `OutboxWriter.publishAs` | yes | yes | fixed here：用 `ProcessUnitOfWork.execute` 包住 `publishAs` |
| `relay/CommandEffectDispatcher.java:29` → `CommandBus.sendAs` | 同一线程 | no | `RegistryCommandBus` 经 `TransactionCommandInterceptor` 自开事务（`AipersimmonDddCqrsAutoConfiguration.java:111`） |
| 应用自定义 `IntegrationEvents`（非 outbox） | 同一线程 | 视实现 | 修复后多包一层 `PROPAGATION_REQUIRED` 事务，对不需事务的实现无害 |
| `ProcessEffectRelay.java:224` markDead + suspend | 已在事务内 | no | 已用 `unitOfWork.execute` |

## 5. Reproduction (test-first)

`aipersimmon-ddd-process-manager-mybatis-plus/.../MybatisProcessEffectRelayTest.java`：新增 `TestFulfilment.Announce` 输入，产出
`PublishIntegrationEvent(Announced)`；`IntegrationEvents` 假件 `TransactionalIntegrationEvents` 在 `publishAs` 里做与 `OutboxWriter` **同一条**检查
（`TransactionSynchronizationManager.isActualTransactionActive()`），不满足即抛同样的 `IllegalStateException`。不引入 outbox 引擎与其 schema：
被测的是 relay 线程有没有事务，而不是 outbox 的 SQL。

- Failing test：`MybatisProcessEffectRelayTest::publishesAnIntegrationEventEffectInsideATransaction` — 修前失败于
  `the integration-event effect is delivered ==> expected: <1> but was: <0>`

## 6. Fix

- Change：`IntegrationEventEffectDispatcher` 增加 `ProcessUnitOfWork` 构造参数，`dispatch` 在 `unitOfWork.execute` 内调用 `publishAs`；
  `AipersimmonDddProcessManagerAutoConfiguration.integrationEventEffectDispatcher` 注入 `ProcessUnitOfWork`，条件加 `ProcessUnitOfWork.class`。
  javadoc 写明：这里"与行同提交的状态变更"就是已随 advance 提交的效果行，所以只包写入本身就是完整保证；
  outbox 提交与 `markDelivered` 之间崩溃会以同一 event id 重投，`OutboxWriter.java:166-172` 把重复 INSERT 折叠到已有行。
- Why this addresses the root cause and not the symptom：边界加在唯一同时接触两个契约的构件上；两侧契约都不动。
- Alternatives rejected：
  - 包住整个 `ProcessEffectRelay.dispatch`——命令 handler 会 join relay 的事务：内层失败毒死共享事务、retry-on-conflict 失效（`TransactionTemplateUnitOfWork.java:12-23`），且 `markDelivered` 与外部副作用绑成一个提交，改变 at-least-once 语义。
  - 放宽 `requireActiveTransaction`——恢复 `44f722e0` 修掉的沉默失效。
  - 把 `markDelivered` 也并进这个事务——需要 relay 感知 dispatcher 类型，换来的只是少一次幂等重投；outbox 已按 event id 去重，不值。
  - 应用侧改用 `DispatchCommand`——目标 BC 是独立服务时不可用，且违反 `design-00004` §3.5。

## 7. Verification

- 修前 §5 测试按预期失败；修后通过。
- `mvn -o -f aipersimmon-ddd/pom.xml -pl aipersimmon-ddd-process-manager-engine,aipersimmon-ddd-process-manager-mybatis-plus -am verify`：BUILD SUCCESS，
  mybatis-plus 模块 130/130（`MybatisProcessEffectRelayTest` 13/13，原 12 + 新 1），Spotless / PMD+CPD / SpotBugs 全过。
- 下游实践项目的 `AutomaticCollectionAcceptanceTest` 两条用例（报告附）需在升级框架后复跑确认，本仓库不含。

## 8. Follow-through

- Detection gap：框架里 `PublishIntegrationEvent` 通路的每个测试都只测一半——`IntegrationEventEffectDispatcherTest` 用记录假件，`ProcessEffectRelayTest` 用 `RecordingDispatcher`，
  `MybatisProcessEffectRelayTest` 只注册 `CommandEffectDispatcher`，`OutboxWriterTest` 在 `@BeforeEach` 里 `setActualTransactionActive(true)`。
  没有 sample 走 process manager + outbox。补的测试把 outbox 的前置条件搬到 relay 的真 DB 测试里；不再加更多。
- Doc verdict：**code was non-conformant**——`PublishIntegrationEvent` javadoc 与 `design-00004` §4.6/§13 所述行为正确，代码没兑现。
  顺带发现 `design-00004:762` 仍写 `IntegrationEvents.publish(...)`，实际自 `issue-00032` 起是 `publishAs`；属陈旧措辞，本 issue 不改。
- Residual state：已 `DEAD` 的效果行与因此 `SUSPENDED` 的实例不会自愈。升级后按 `design-00004` 运维通路 resume 实例并重投效果；
  框架内无受影响数据。
- Open Question（domain owner）：outbox + process manager 的组合是 §3.5 的标准形态，却没有 sample 覆盖。是否补一个 sample 或把 `MybatisProcessEffectRelayTest`
  升级为用真 `OutboxWriter`？本 issue 不替 owner 决定。

## Links

- Blocks: design-00004-durable-process-manager-runtime
- Related: issue-00032-integration-event-effect-replay-mints-new-eventid（同一 dispatcher 的身份缺陷）、
  issue-00044-integration-events-bypass-outbox-kafka-at-runtime（同类"两个正确的局部装配合起来错"）
