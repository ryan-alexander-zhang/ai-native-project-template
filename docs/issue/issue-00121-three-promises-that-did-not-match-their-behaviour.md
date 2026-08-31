---
id: issue-00121-three-promises-that-did-not-match-their-behaviour
type: issue
status: resolved
blocks: [issue-00119-ten-majors-were-never-scheduled]
---

# 三条"文档承诺 A、代码行为 B"的残余

`issue-00119` 排期第 2 档。三条放在一起，因为它们是**同一个毛病**——
正是 `report-00003` §0 点名的第二个系统性主题，而那个主题号称已由 `issue-00107` 收口。

## 一、`domainEvents()` 承诺快照，返回的是活视图

javadoc 写着 "An unmodifiable snapshot"，实现是 `Collections.unmodifiableList(domainEvents)`——
**不可变视图 ≠ 快照**。同步监听器在事件发布途中往同一个聚合再记一条，
`publishAll` 的迭代就从**发布器内部**抛 `ConcurrentModificationException`，
离真正的起因（那个监听器）很远。

**它是怎么活下来的**：现有测试就叫 `domainEvents_returnsAnUnmodifiableSnapshot`，
而断言**只测了 unmodifiable 那一半**——`assertThrows(UnsupportedOperationException, events::add)`。
不可变视图同样通过这条断言。**测试名声称的东西比它断言的东西多。**

### 只改成 `List.copyOf` 是错的，而且更糟

`publishAndClear` 原来是两步：

```java
publishAll(aggregate.domainEvents());   // 发布快照
aggregate.clearDomainEvents();          // 清空全部
```

改成快照之后 CME 没了，但监听器在发布途中记下的那条事件，会被随后的 `clear()` **一并清掉**——
**把一次响亮的崩溃换成了一条被静默丢弃的领域事件**。这是同一个问题的更坏答案。

### 所以两半一起改

新增 `drainDomainEvents()`：**取走并清空，一步完成**。
之后再记的事件仍留在聚合上，看得见。`publishAndClear` 改为先 drain 再发布。

**而在同一个聚合上记新事件这件事本身，被拒绝而不是默默接受**：
`publishAndClear` 按契约是在**根已经持久化之后**调用的，所以此时记下的事件，
它所宣告的状态变更**从来没有被写入过**。发布它等于描述一件没发生的事；
静默丢弃则是领域事件凭空消失。两者都不行，于是抛异常并说明该怎么做
（在保存前改，或作为另一个用例处理）。

在**别的**聚合上记事件不受影响——那是响应事件的常规做法，不是滥用，必须能用。

## 二、命令失败只记 DEBUG

默认 INFO 阈值下，**一条失败的命令一行日志都没有**——而这恰是运维最想看到的那一条。

但一律升到 WARN 是另一个错误答案：被拒绝的订单是一个**正常工作的系统的正常产出**，
拒绝率高的路径会把真正值得读的故障淹掉。

**所以级别跟着框架自己已经画好的那条线走**，用的是它自己基类 javadoc 里的原话——
`DomainException` 与 `ApplicationException` 存在的意义就是
"so callers can distinguish business-rule failures from technical faults"：

| | 级别 | 栈 |
|---|---|---|
| 业务规则拒绝 | **INFO** | 无——一条正常工作的规则的栈是噪音；消息 + MDC 上的 correlationId 已足够追踪 |
| 其余 | **WARN** | **有**——总得有人看到栈，而这里是**唯一**能看到每一条命令的地方：relay、deadline worker 与 HTTP 请求不共用任何外层处理器 |

**按类型匹配，绝不按类名**——按名字匹配正是 `issue-00102` 踩过的坑
（Hibernate 的同名异常被判成 `NOT_STARTED`）。

## 三、兜底 500 处理器不记日志

`handleUnexpected` 造一个 ProblemDetail 就返回，**一行日志都没有**。
生产环境一个 NPE 只在 access log 里留下一个 500，没有任何可调试的东西。

其余每一个 handler 都是把框架**认识**的异常映射到客户端可据以行动的状态码；
这一个恰恰相反——它接住的是没人预料到的东西，而这正是栈是唯一记录的情形。

现在先 `log.error(..., ex)` 再作答，**响应仍然什么都不说**：
运维需要知道什么、客户端可以被告知什么，是两个不同的问题。

## 验证

PIT 在测试写好之前就**先失败了**：`drainDomainEvents()` 无测试覆盖，
core 的 90% 变异门禁直接把构建打回（89%）。补完后 core 与 application 双双 100% mutation。

新增/加强的断言：快照不是视图（新）、drain 返回并清空、drain 之后记的事件仍在、
监听器动别的聚合不受影响、监听器动同一聚合被拒绝且**真实的事件照常发出**。

## 关联

- 父：[issue-00119-ten-majors-were-never-scheduled](issue-00119-ten-majors-were-never-scheduled.md)（排期第 2 档）
- 同一个系统性主题的上一批：[issue-00107-silent-degradations-become-loud-failures](issue-00107-silent-degradations-become-loud-failures.md)
- 按类名匹配异常的教训：[issue-00102-failed-operations-are-not-recorded-under-an-outer-transaction](issue-00102-failed-operations-are-not-recorded-under-an-outer-transaction.md)
- 聚合与事件记录的契约：[design-00011-aggregate-persistence-contract](../design/design-00011-aggregate-persistence-contract.md)
