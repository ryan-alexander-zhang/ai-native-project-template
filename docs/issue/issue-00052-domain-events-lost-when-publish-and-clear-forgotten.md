---
id: issue-00052-domain-events-lost-when-publish-and-clear-forgotten
type: issue
status: resolved
blocks: [report-00001-ddd-framework-review]
---

# `save` 与 `publishAndClear` 是两次手工调用：漏掉第二次则领域事件静默丢失，无异常、无日志、无任何信号

## 问题（现状，file:line 为证）

- **等级：High（静默数据丢失；触发条件是「开发者忘了一行」，即长期必然发生）**。
- 当前契约要求每个写操作**手工调用两次**，样例 4 处全是这个形状：
  - `PlaceOrderHandler.java:113-114`：`orders.save(order); domainEvents.publishAndClear(order);`
  - `ConfirmOrderHandler.java:40-41`、`CancelOrderHandler.java:38-39`、`FulfilmentTrigger.java:45-46` 同构。
- 契约把责任推给调用方，且**允许两个不同位置**承担：`application/DomainEvents.java` 的 Javadoc 写
  「a repository (**or the handler**) calls `publishAndClear(...)`」——"or" 意味着两处都可能以为对方做了。
- `TransactionCommandInterceptor.java:13-16` 的 Javadoc 明确声明它**不**负责 drain：
  「This interceptor owns only the transaction boundary. Draining an aggregate's recorded events is done
  where the aggregate is saved」。即**没有任何兜底**。
- 漏掉时的表现：`registerEvent` 把事件存进 `AbstractAggregateRoot.domainEvents`（`:23`）后**无人读取**，
  聚合对象随事务结束被 GC。**不抛异常、不打日志、不进 outbox**。

后果：状态改了但事实没发出去。下游表现为流程管理器不推进、投影不更新、集成事件缺失，而**故障点与现象相距很远**
（现象在下游、原因在上游某个 handler 少一行），属最难定位的一类缺陷。单元测试若未显式断言事件，**也照样通过**。

## 根因（第一性）

1. **观察 vs 期望**：期望「聚合记录的事实一定会随状态变更一起发布或一起回滚」；实际「发布是一次独立的、可被遗忘的
   手工动作，遗忘无代价」。
2. **最小机制**：`registerEvent` 与 `publishAndClear` 之间**没有强制配对**。`domainEvents` 是聚合上的私有集合，
   只有显式 `publishAndClear` 才会读它；集合非空**不是**任何检查的输入。于是「已记录但未发布」是一个
   **完全合法、无声的终态**。
3. **真根因**：正确性依赖开发者记忆，而框架**持有足以自动化它的全部信息**——事务边界在
   `TransactionCommandInterceptor` 手上，聚合的未发布事件在 `domainEvents()` 里可读。把一个可自动化的不变式
   留给人去记，是设计缺陷而非使用错误。
4. **排除的伪根因**：不是「Javadoc 写得不够清楚」。Javadoc 已经写得很清楚（甚至解释了为什么在 save 处 drain）。
   文档无法阻止遗漏；只有失败会。

## 复现（test-first）

新增 `aipersimmon-ddd-cqrs-spring` 装配测 `UnpublishedDomainEventsGuardTest`：

1. 定义一个 handler，`aggregate.someBehaviour()`（内部 `registerEvent`）后**只**调用仓储 `save`，
   **不**调用 `publishAndClear`。
2. `commandBus.send(cmd)`。
3. **断言（现状 → 失败）**：命令正常返回，`DomainEvents` 收到 0 个事件，事务正常提交——即事实被静默吞掉。
4. 修复后：事务提交前抛出，明确指出「聚合 X 有 N 个未发布的领域事件」。

配套在样例保留一条正向回归：`OrderingFlowTest` 断言下单后 outbox 中确有 `OrderPlacedEvent`
（防止兜底改动把正常路径一起挡掉）。

## 修复

批次 A 采用 **fail-loud 兜底**（不改变现有 drain 位置，只让遗漏无法静默）：

- 在 `TransactionCommandInterceptor` 提交**前**检查本次命令内被保存过的聚合是否仍有未清空的 `domainEvents`，
  有则抛出（回滚整个命令）。需要一个轻量的「本事务内保存过哪些聚合」登记点——由批次 B 的仓储基类天然提供；
  批次 A 先在样例仓储的 `save()` 末尾调用 `domainEvents.publishAndClear(order)`，把 4 处 handler 里的手工调用
  **收口到仓储一处**，并同步修正 `DomainEvents` / `TransactionCommandInterceptor` 的 Javadoc（去掉 "or the handler"
  这个歧义授权）。
- 批次 B 由 [[design-00011-aggregate-persistence-contract]] 的仓储基类把「save + publish」变成一次调用，
  连「仓储里也可能忘」都消除。

**注意改动面**：`TransactionCommandInterceptor`（新增提交前校验 + Javadoc）、`DomainEvents` Javadoc、
样例 4 处 handler（删除手工 `publishAndClear`）、样例 2 个仓储（`MyBatisOrders` / `MyBatisCustomers` 等在
`save` 末尾 drain）。不改 `DomainEvents` 接口签名。

## 验证结果（批次 A，已修复）

`publishAndClear` 已从 4 处 handler（`PlaceOrderHandler` / `ConfirmOrderHandler` / `CancelOrderHandler` /
`FulfilmentTrigger`）全部移除，收口到 3 个仓储的 `save()` 末尾；`ConfirmOrderHandler` / `CancelOrderHandler` /
`FulfilmentTrigger` 因此不再需要 `DomainEvents` 协作者，构造签名一并收窄。

`DomainEvents` 的 Javadoc 已删去 "or the handler" 这一歧义授权，并明确写出「handler 必须**不**调用它」及原因；
`TransactionCommandInterceptor` 的说明同步更新。

事件仍然到达：样例全量 `verify` 通过，其中 `OrderingFlowTest` / `ReviewFlowTest` /
`PaymentCompensationFlowTest` 依赖领域事件驱动整条跨上下文流程走到终态——若收口丢了事件，这些测试会立刻变红。

**本阶段有意不实现报告 P0-2 的方案 B（提交前兜底扫描）**：一旦发布收口进仓储 `save()`，「被 save 过的聚合」
其事件集合恒为空，该检查退化为恒真断言。理由记录在 [[plan-00013-phase-one-correctness-remediation]]。

## 关联

- [[report-00001-ddd-framework-review]]（P0-2，本 issue 的来源）
- [[plan-00013-phase-one-correctness-remediation]]
- [[design-00011-aggregate-persistence-contract]]
- [[issue-00051-aggregates-have-no-optimistic-locking]]（同一处仓储调用点，批次 A 一并修）
- [[analysis-00001-domain-event-publishing]]（当前「在 save 处 drain」这一选择的原始分析）
