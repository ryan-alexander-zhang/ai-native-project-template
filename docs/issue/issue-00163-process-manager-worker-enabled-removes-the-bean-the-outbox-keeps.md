---
id: issue-00163-process-manager-worker-enabled-removes-the-bean-the-outbox-keeps
type: issue
status: open
---

# 同名的 `relay.enabled=false` 在 outbox 是"停调度"，在 process-manager 是"删 bean"（P2，一致性/可测性）

2026-08-04 写 S9 的 samples 时撞到（`aipersimmon-ddd-samples/s09-eventual-consistency-process-manager`）。
两个兄弟模块给形状相同的属性两种不同语义，其中一种把库自己文档化的测试手法拿掉了。

## 现象

照 S4 的做法给 process-manager 的测试关掉后台轮询：

```yaml
aipersimmon.ddd.process-manager.effect-relay.enabled: false
aipersimmon.ddd.process-manager.deadline-worker.enabled: false
```

启动成功，但注入 `ProcessEffectRelay` 失败：

```
No qualifying bean of type 'com.aipersimmon.ddd.processmanager.engine.relay.ProcessEffectRelay'
```

于是"关掉调度、由测试自己驱动一次投递"这条路走不通——而这正是 outbox 那边的标准写法。

## 两边的差别，各一行代码

**outbox：属性只管调度器。** relay bean 无条件注册：
`aipersimmon-ddd-outbox-engine/src/main/java/com/aipersimmon/ddd/outbox/engine/autoconfigure/AipersimmonDddOutboxEngineAutoConfiguration.java:101`

```java
public OutboxRelay outboxRelay(
```

条件挂在**调度器**上（同文件 `:136-141`）：

```java
@ConditionalOnProperty(name = "aipersimmon.ddd.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public OutboxRelayScheduler outboxRelayScheduler(OutboxRelay outboxRelay) {
```

S4 的 `OutboxPublicationTest` 因此可以写 `properties = {"aipersimmon.ddd.outbox.relay.enabled=false"}` 然后
`@Autowired OutboxRelay relay` 并逐次 `relay.relay()`，测试类 javadoc 也把这条写成"the use the library
documents for `relay.enabled=false`"。

**process-manager：属性管 bean 本体。**
`aipersimmon-ddd-process-manager-engine/src/main/java/com/aipersimmon/ddd/processmanager/engine/autoconfigure/AipersimmonDddProcessManagerAutoConfiguration.java:294-298`

```java
@ConditionalOnProperty(
    prefix = "aipersimmon.ddd.process-manager.effect-relay",
    name = "enabled",
    matchIfMissing = true)
public ProcessEffectRelay processEffectRelay(
```

`deadline-worker.enabled` 同构（同文件 `:335-339`）。调度器 `:390-396` 用
`ObjectProvider<ProcessEffectRelay>` 取，取不到就不排它——所以关掉之后**工作单元本身消失了**，不只是不再
被定时驱动。

## 代价

- **可测性**：想确定性地驱动一个流程（S9 的每个断言都建立在"现在投递下一个 effect"之上），只能改用
  "把 poll-delay 设成 1h"这种绕法——能用是因为调度器的 initialDelay 等于 pollDelay（`ProcessWorkerScheduler:102-104`），
  但这依赖一个实现细节，而不是一个被文档化的开关。
- **部署形态**：想让"只有专用实例跑 relay"的部署也拿不到干净写法——其余实例连手动补投的能力都没有。
- **一致性**：两个模块的 `*.enabled` 读起来一样，含义不一样，而且更常用的那个（outbox）建立的直觉是错的。

## 修复要求

**把工作单元与它的调度分开，两个模块都一样。** process-manager 侧的最小改动是把
`effect-relay.enabled` / `deadline-worker.enabled` / `parked-input-worker.enabled` 的条件从 bean 方法挪到
`processWorkerScheduler` 里的排程决定（那里已经逐个判断 null，加一层属性判断是自然的）。要点：

- 默认值不变（缺省即开），所以对既有部署零行为变化；
- 保留 bean 意味着 `@ConditionalOnMissingBean` 语义不变，应用仍可自带实现；
- `CONFIGURATION.md` 里这三条属性的说明该改成"停调度，不删组件"，并且指出这就是测试里驱动一次投递的办法
  ——与 outbox 那节的措辞对齐。

## 复现

`aipersimmon-ddd-samples/s09-eventual-consistency-process-manager/src/test/java/com/example/samples/s09/FlowTestBase.java`：
把两条 `poll-delay=1h` 换成 `enabled=false`，跑 `mvn -pl s09-eventual-consistency-process-manager -am verify`，
`FulfilmentFlowTest` 的 11 个用例全部因为注入失败而 error。
