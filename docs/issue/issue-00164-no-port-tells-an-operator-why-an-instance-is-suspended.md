---
id: issue-00164-no-port-tells-an-operator-why-an-instance-is-suspended
type: issue
role: main
status: open
---

# 没有任何端口能回答"这个流程为什么挂起"——只有"哪件事挂住了它"（P2，可观测性缺口）

2026-08-04 写 S9 的 samples 时撞到（`aipersimmon-ddd-samples/s09-eventual-consistency-process-manager`）。
运维界面能用库的端口做出一半，另一半必须绕过端口直接读表。

## 现象

一个流程因为某个 effect 重试耗尽而 SUSPENDED。库给出的读端口是
`aipersimmon-ddd-process-manager/src/main/java/com/aipersimmon/ddd/processmanager/runtime/ProcessQuery.java`，
两个方法：`find(ProcessRef)` 与 `findRef(type, businessKey)`。`find` 返回的
`ProcessView`（同模块 `runtime/ProcessView.java:28-38`）带 `suspensionReason`，实测内容是：

```
effect 019fcaa1-...#0 exhausted retries
```

即**哪件工作挂住了它**。真正的原因（那次失败的异常）写在 effect 行的 `last_error` 列上——
`ProcessEffectRelay:217-234` 的 `onFailure` 把 `describe(failure)` 交给 `effects.markDead(...)`，而
`instances.suspend(...)` 只拿到上面那句拼出来的话。

于是"为什么"只能这样拿到：

```sql
SELECT last_error FROM aipersimmon_process_effect WHERE effect_id = ?
```

`ProcessEffectStore` 在 `com.aipersimmon.ddd.processmanager.engine.store`——引擎内部包，不是给消费者用的
端口；`ProcessOperations` 只有三个写动作（redrive effect / redrive deadline / cancel），没有读。所以一个
运维端点要么少了最重要的一栏，要么在 controller 里写 SQL。

## 为什么现状是合理的一半

分层是对的：实例表记"哪件事"，effect 表记"那件事怎么失败的"，两处都不冗余。写入侧也没问题——
把异常文本塞进 `suspension_reason`（512 字符）会截断堆栈并让同一实例的多次挂起互相覆盖。

缺的只是**读**：没有端口把这两行拼起来。

## 修复要求

**给 `ProcessQuery` 加一个只读方法，返回挂住这个实例的工作及其最后一次错误。** 例如：

```java
List<StuckWork> stuckWork(ProcessRef ref);   // kind(EFFECT/DEADLINE/PARKED_INPUT), workId, attempts, lastError, nextAttemptAt
```

要点：

- 放在 `ProcessQuery`（消费者端口）而不是 `ProcessOperations`，因为这是读；
- 返回值必须是端口层自己的记录，不能泄露 `ClaimedEffect` 之类引擎内部类型；
- `attempts` 与 `nextAttemptAt` 一起给，否则运维分不清"还在退避"与"已经放弃"；
- 顺带补 `CONFIGURATION.md` / process-manager 的 README 一句：诊断一次挂起需要两条信息，实例说哪件事、
  这个方法说为什么。

## 复现

`aipersimmon-ddd-samples/s09-eventual-consistency-process-manager/src/test/java/com/example/samples/s09/StuckFlowTest.java`
的 `aflowStuckOnAMisconfigurationSuspendsItselfAndAnOperatorCanResumeIt`：`suspensionReason` 只含
`exhausted retries` 与 effect id，`GALLERY`（真实原因）只在 `aipersimmon_process_effect.last_error` 里。
