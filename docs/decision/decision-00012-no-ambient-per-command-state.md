---
id: decision-00012-no-ambient-per-command-state
type: decision
role: main
status: active
parent:
---

# 禁止 ambient 每命令状态:领域事件在 save 处排空,移除 `AggregateCollector`/ThreadLocal

固化"命令执行期间的**每命令状态**(touched 聚合、后续要传播的调用元数据)该放哪、怎么传"。
起因是既有的 `ThreadLocalAggregateCollector`:一个**单例端口**却持有每命令可变状态,只能靠线程绑定
求并发安全。本决策废除这条线程域机制,并把领域事件的排空改到**聚合被保存处**。承接
[[decision-00009-event-type-markers-and-handler-contracts]] 与
[[decision-00011-cqrs-write-contracts-as-interfaces-not-annotations]] 的"显式契约优先"取向,
并**supersede**下列文档中关于 `AggregateCollector` 的描述:[[design-00001-aipersimmon-ddd-and-scaffold]] §5.10/§5.11、
[[analysis-00006-ddd-building-blocks-library]] §五及模块表、[[decision-00010-command-handler-reuse-and-cross-aggregate-placement]] 规则二表格。

## 结论先行

> **每命令状态必须是一个显式、生命周期明确的对象,绝不能是共享单例上的 ambient(线程域)状态。**
> 据此:**移除 `AggregateCollector` 端口与 `ThreadLocalAggregateCollector` 实现**;聚合记录的领域事件改由
> **保存它的一方在 save 处、同事务内排空**——即 repository(或 handler)在持久化聚合根后调用
> `DomainEvents.publishAndClear(root)`。`TransactionCommandInterceptor` 只保留**事务边界**这一项职责,
> 不再持有 collector,也不再集中 drain。事务原子性不变:发布跑在同一事务内,事务型发布者(outbox /
> `@TransactionalEventListener`)与状态变更一起提交或回滚。

## Context

原设计(见 [[analysis-00005-structure-2-event-flow-and-cqrs]] §5 的务实点、[[analysis-00006-ddd-building-blocks-library]] §五)为解决
"JDBC/MyBatis 没有 EF 式 ChangeTracker,拦截器不知道该 drain 哪些聚合"这一问题,引入了
`AggregateCollector`:repo/handler 保存时 `register(aggregate)`,`TransactionCommandInterceptor` 在 handler
返回后从 `collected()` 集中排空事件、`clear()`。因为 collector 是**单例 bean 却要存每命令可变状态**,
唯一的并发安全实现只能是 `ThreadLocal`(`ThreadLocalAggregateCollector`)。

这条线程域机制的代价:
- **可测性差 / 隐式依赖**:touched 聚合藏在线程状态里,不在任何签名上。
- **对虚拟线程 / 响应式脆弱**:与成熟框架的演进方向相反——Axon 5 正是把线程绑定的 `UnitOfWork` 换成**显式**
  `ProcessingContext`,不再依赖 `ThreadLocal`。
- **概念污染**:一个"端口"实为线程本地容器。

"禁止 ThreadLocal"这条约束,本质要求消掉"单例端口持有每命令状态"这件事本身,而不是换个存储后端。

## Decision

1. **删除** `aipersimmon-ddd-cqrs` 的 `AggregateCollector` 端口与 `aipersimmon-ddd-cqrs-spring` 的
   `ThreadLocalAggregateCollector` 实现,以及其 autoconfig bean。库内不再有任何自建 `ThreadLocal`。
2. **排空下沉到 save 处**:聚合根自带事件(`AbstractAggregateRoot`),"哪个聚合变了"的答案就是"谁刚
   save 的那个"。在 `DomainEvents` 上新增默认方法 `publishAndClear(AbstractAggregateRoot<?>)`
   (先 `publishAll(domainEvents())` 再 `clearDomainEvents()`);repository(或 handler)在持久化聚合根后、
   同事务内调用它。**不需要旁路收集器**。
3. **`TransactionCommandInterceptor` 只做事务边界**:`intercept` = `unitOfWork.execute(invocation::proceed)`,
   不再持有 `AggregateCollector` / `DomainEvents`,不再集中 drain。它仍是内置链最内层(order 200)。
4. **原则固化**:每命令(或每消息)状态一律以**显式对象**承载、经**显式参数**传递,不得放进共享单例的
   线程域 / scope 代理等 ambient 载体。本原则同时约束后续设计(见 Consequences)。

## Rationale

### 命题一 —— save 处排空同样解决"无 ChangeTracker",且无 ambient 状态

collector 存在的唯一理由是回答"该 drain 哪些聚合"。但 repository 保存聚合时**本就知道**它是谁,聚合又自带
事件。于是"谁 save、谁排空"直接回答了同一问题,ChangeTracker 缺失的务实点依然被覆盖——只是换成了不需要
线程域状态的解法。这是对原问题的**更干净的解**,不是推翻原问题。

### 命题二 —— 事务原子性不降级

排空在 save 处发生,而 save 跑在 `TransactionCommandInterceptor` 打开的事务内,故发布仍在同一事务内:
- **outbox**:事件行与状态变更同事务写入,一起提交/回滚。
- **`@TransactionalEventListener`**:投递绑定到提交后阶段,命令回滚则从不投递。

唯一行为差异:发布**时机**从"命令末尾集中" → "每次 save"。对上述事务型发布者完全等价;只有**裸同步进程内
监听器**会更早看到事件——而这类监听器本就不具回滚安全性,不是本库推荐的默认。库内端到端测试(happy /
回滚不投递 / 校验先于事务 / 查询侧)在改造后全绿,回滚用例以事务感知的 capturer 证明"命令失败则一事件不投递"。

### 命题三 —— 与既有"显式优先"判据同源

[[decision-00011-cqrs-write-contracts-as-interfaces-not-annotations]] 选"编译期强制的类型契约"而非"被动注解";
本决策选"显式对象 + 显式传参"而非"隐式线程状态"。二者是同一取向在**状态传播**维度的延伸:让依赖显现在
签名与类型上,而非藏进容器魔法。

## Consequences

- `aipersimmon-ddd-cqrs`:移除 `AggregateCollector`;package-info 相应更新。
- `aipersimmon-ddd-cqrs-spring`:移除 `ThreadLocalAggregateCollector` 与 collector bean;
  `TransactionCommandInterceptor` 仅注入 `UnitOfWork`;package-info 更新。
- `aipersimmon-ddd-application`:`DomainEvents` 新增 `publishAndClear` 默认方法(排空的复用入口)。
- **文档订正**:design-00001 §5.10/§5.11、analysis-00006 §五及模块表、decision-00010 规则二表格中
  `AggregateCollector`/`ThreadLocalAggregateCollector` 的描述改为"save 处排空",并指向本决策。
- **对后续设计的约束(同一原则的推论,尚未落地,留待各自决策)**:
  - 入站集成事件的调用元数据不得走线程域补齐;若需在进程内桥接,应以**类型化信封**显式携带(payload + 元数据)。
  - 命令派发的因果元数据(correlation/causation)应作为**显式的 `CommandDispatchContext`** 经 `CommandBus`
    重载与拦截器参数传播,不得 ambient——此为独立后续决策,不在本决策范围内落地。
- 无遗留 ArchUnit 规则依赖 `AggregateCollector`(已确认),故无规则改动;可选新增一条"库内禁止自建
  `ThreadLocal`"的规则以固化本约束(待定,非本决策强制)。

## Sources

内部:

- `aipersimmon-ddd/aipersimmon-ddd-application/.../DomainEvents.java` —— `publishAndClear` 排空入口。
- `aipersimmon-ddd/aipersimmon-ddd-cqrs-spring/.../TransactionCommandInterceptor.java` —— 仅事务边界。
- `aipersimmon-ddd/aipersimmon-ddd-core/.../AbstractAggregateRoot.java` —— 聚合自带事件登记/清空。
- `aipersimmon-ddd/aipersimmon-ddd-cqrs-spring/.../CqrsPipelineTest.java` —— 改造后端到端 4/4,回滚不投递。
- [[decision-00009-event-type-markers-and-handler-contracts]]、[[decision-00010-command-handler-reuse-and-cross-aggregate-placement]]、[[decision-00011-cqrs-write-contracts-as-interfaces-not-annotations]]、[[analysis-00005-structure-2-event-flow-and-cqrs]]、[[analysis-00006-ddd-building-blocks-library]]。

外部:

- Axon Framework 5 —— 以显式 `ProcessingContext` 取代线程绑定的 `UnitOfWork`(为响应式 / 虚拟线程去除 `ThreadLocal` 依赖)。https://docs.axoniq.io
- Spring Framework —— `@TransactionalEventListener`(提交后阶段投递,回滚不投递)。https://docs.spring.io
