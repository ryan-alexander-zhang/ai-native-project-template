---
id: decision-00008-event-subscriber-layer-placement
type: decision
role: main
status: active
parent:
---

# 事件订阅者的分层归属:领域事件归 application、集成事件归 adapter 并转 command

固化"事件的**订阅侧**放哪一层"的规则。承接 [[analysis-00002-domain-vs-integration-events]]
(为何区分两类事件)与 [[analysis-00009-saga-implementation-deep-dive]] §四/§五(saga 的事件驱动)。
每条主张都由 `docs/reference/` 笔记原文支撑(见 **Sources**)。

## Context

`aipersimmon-ddd-scaffold/multi-module` 把适配器拆成两个模块:

- `ordering-adapter` —— **入站/驱动适配器**:`web/`(REST)+ `messaging/`。
- `ordering-infrastructure` —— **持久化/被驱动适配器**:`InMemoryOrders`、`InMemoryCustomers`、
  `InMemoryOrderFulfilmentSagaStore`(仓储/存储实现)。

现状问题:`ordering-adapter/messaging/OrderFulfilment` 用 `@EventListener` 订阅了**内部领域事件**
`com.example.ordering.domain.order.OrderPlacedEvent` 来启动 saga。经核查,**它是整个 `ordering-adapter`
模块里唯一 import `…domain` 的类**;同模块的 `web/OrderController` 只把 HTTP 请求翻译成 command
(`commandBus.send(request.toCommand())`),完全不碰 domain。

这引出一个分层问题:**领域事件的订阅者该不该放在 adapter 层?adapter 有没有理由依赖 domain?**

## Decision

1. **领域事件的订阅者属于 application 层**(或 domain/Core),**不属于** adapter/infrastructure。
2. **集成事件的订阅者属于 adapter 层**(入站适配器),adapter 不持有编排/领域逻辑。进入
   application 之后按事件的**用途二分**:
   - **驱动聚合直接变更**的集成事件 → adapter 解包后**翻译成 command** 经 `CommandBus` 进入
     application → `CommandHandler` → 聚合。
   - **推进 saga**的集成事件 → adapter 解包成 correlationId,**递交给 process manager**;saga 是
     event-driven 的,它反应该 occurrence 后**自行发出 command**(不把喂给 saga 的事件包成 command)。
3. 依赖方向上,**adapter 可以依赖 domain(向内依赖合法)**;但在"入站适配器 / 持久化适配器分模块"
   的划分下,**入站 `adapter` 模块没有正当理由依赖 domain**——它只做"传输 ↔ command/query"。
   真正有理由依赖 domain 的是 **`infrastructure`**(仓储映射、ACL 把外部模型翻译成 domain 模型)。

因此:把 `OrderPlacedEvent` 的订阅移出 `ordering-adapter`,`ordering-adapter` 随之**去掉对
`ordering-domain` 的依赖**,与 `web/OrderController` 的范式对称。

## Rationale:两个独立命题,依据分开

### 命题 A —— adapter→domain 的**依赖方向**合法(不是本决策要改的)

- `clean-architecture`:*"Dependencies point inward; `Core` is the center."* / *"`Infrastructure → Core + UseCases`; `Web → Infrastructure + UseCases`."*
- `domain-driven-hexagon`:*"Dependency Inversion: dependencies point inwards; the Core stays framework/tech-agnostic."*
- `modular-monolith-with-ddd`:*"Domain must not depend on Infrastructure"*(仅禁 domain→infra,未禁 adapter→domain)。

### 命题 B —— 领域事件**订阅者**统一放在 application/domain,而非 adapter(本决策依据)

| 参考 | 原文 | 归属 |
| --- | --- | --- |
| `clean-architecture` | *"Domain events raised via `RegisterDomainEvent(...)`; handlers co-located in `Core/.../Handlers` (domain behavior stays in Core)."* | Core |
| `spring-modulith-with-ddd` | *"App services `CatalogManagement`, `CirculationDesk` (`@Service @Transactional`)."* + *"Listeners use `@ApplicationModuleListener`"* | application |
| `ddd-by-examples-library` | *"Cross-context integration via event handlers — `book/application/CreateAvailableBookOnInstanceAddedEventHandler` … `PatronEventsHandler`."* | application |
| `ddd-by-examples-factory` | *"Define a domain-event port in the model (`DemandEvents`), implement propagation in the app layer, so contexts integrate without the domain knowing Spring or transports."* | application |
| `modular-monolith-with-ddd` | *"Application (CQRS handlers, domain events, integration events, internal commands)"* | application |

**没有任何一份参考**把领域事件订阅者放在 infrastructure/adapter,也没有一份明文称其为 smell——
但一致的放置方向就是 application/domain。

### 命题 C —— 集成事件入站:订阅在 adapter,进 application 后按用途二分

订阅位置在 adapter(不 reach into 内部领域事件);进入 application 后**分两种,不可一概"翻译成 command"**:

**(C1) 驱动聚合的集成事件 → command → handler → 聚合**

- `modular-monolith-with-ddd`:*"Inbox (at-least-once, idempotent): a received event is persisted to an Inbox table; a worker reads unprocessed rows, **runs an internal command per event**, marks processed — duplicates converge."*
- `domain-driven-hexagon`:*"Multiple entry adapters per use case are normal (http/cli/message/graphql), each **mapping to the same Command/Query**."*

**(C2) 推进 saga 的集成事件 → 递交给 process manager(saga 反应后自行发 command)**

- saga/process manager 是 event-driven 的协调者:它**订阅事件**、反应后**派发 command**。把要喂给 saga 的
  集成事件包成 command 是多此一举——command 是 saga 的**产物**,不是它的输入。这与 [[analysis-00009-saga-implementation-deep-dive]] §四(saga 反应 occurrence、发出 command)一致,亦即 Axon `@SagaEventHandler` 反应事件 + `CommandGateway.send` 的模型。

**两种情形当前 sample 均已体现**(`aipersimmon-ddd-scaffold/multi-module`):

| 情形 | 代码 | 链路 |
| --- | --- | --- |
| C1 驱动聚合 | `inventory-adapter/messaging/OrderPlacedListener` | `OrderPlaced`(集成事件)→ `commandBus.send(new ReserveStock(...))` → `ReserveStockHandler implements CommandHandler` → `stock.reserve()` / `stocks.save()`(Stock 聚合)。该 listener 零 domain 依赖。 |
| C2 推进 saga | `ordering-adapter/messaging/OrderFulfilment` | `StockReserved`/`StockReservationFailed` → `process.onStockReserved(orderId)`(`OrderFulfilmentProcessManager`,`@ProcessManager`,非 CommandHandler)→ saga 反应后 `commandBus.send(new ConfirmOrder/CancelOrder)` → 各自 handler → Order 聚合。 |

`web/OrderController`(HTTP → command,零 domain)是 C1 在**同步入站**上的同构证据。

> 订正记录:本命题初稿曾笼统写作"集成事件订阅者……翻译成 command"。经核对 sample 数据流,
> 推进 saga 的集成事件(如 `StockReserved`)不应包成 command,而应递交给 process manager;
> 故补上 C1/C2 二分。

### C1/C2 的业界对应与 sample 异同

C1、C2 不是本项目自创,业界都有明确正名与框架实现;当前 sample 是它们的**框架无关手搓版**。

**业界正名**

| 本项目 | 业界正名 | 一句话 |
| --- | --- | --- |
| C1 集成事件→command→聚合 | **Messaging Endpoint / ACL → Command**(Hohpe EIP);Eventuate 的 message→command;Grzybek 的 inbox→internal command | 入站消息在边界翻译成命令,命令处理器改一个聚合 |
| C2 集成事件→process manager→发 command | **Process Manager 模式**(Hohpe EIP;定义即"维护流程状态、接收事件/回复、据此发送命令");Axon `@SagaEventHandler` | 协调者订阅事件、维护状态、派发命令;command 是它的产物不是输入 |

**docs/reference 原文**

- C1 —— `modular-monolith-with-ddd`:*"Inbox (at-least-once, idempotent): a received event is persisted to an Inbox table; a worker reads unprocessed rows, **runs an internal command per event**, marks processed"*;*"Internal commands represent deferred work triggered by inbox processing."* 
- C1 —— `domain-driven-hexagon`:*"Multiple entry adapters per use case are normal (http/cli/message/graphql), each **mapping to the same Command/Query**."*
- C2 —— `axon-framework`:*"`@Saga` coordinates long-running, multi-aggregate processes (**process-manager pattern**)."* / *"`@SagaEventHandler(associationProperty = "...")` **routes each event to the right saga instance**"* / *"handlers validate + emit events; a separate handler mutates state"*(decide vs. apply)。

**大厂 / 框架实践**

- C1:Eventuate Tram(消息→命令,配 Idempotent Consumer + inbox)、Kamil Grzybek modular-monolith(inbox→internal command)、NServiceBus(`IHandleMessages<T>` → `Send` 命令)、六边形(primary adapter → Command/Query 端口)。
- C2:Axon(`@Saga` + `@SagaEventHandler` 反应事件、`CommandGateway.send` 发命令、`DeadlineManager` 管超时补偿)、Eventuate Tram Sagas(反应 reply/event → 向参与方 send 命令)、NServiceBus Sagas(`IAmStartedByMessages`/`IHandleMessages` → 发命令)、Camunda/Zeebe(BPMN 被 message 推进 → service task 派命令)。

**sample 异同**

- **结构一致**:C1 的 `inventory-adapter/OrderPlacedListener` = Eventuate/Grzybek 的 message→command 一比一;C2 的 `ordering-adapter/OrderFulfilment` + `OrderFulfilmentProcessManager` = Axon `@SagaEventHandler` + `CommandGateway` 的手搓版。
- **差异一(刻意)**:Axon 里 saga 用 `@SagaEventHandler(associationProperty=...)` 直接订阅事件、框架自动按关联属性路由;本项目多一个 adapter 先把 `StockReserved` 解包成 `orderId` 再喂给 process manager——即 **adapter 手工干了 `associationProperty` 自动做的事**,以此让 process manager 不认传输/契约类型(签名是 `onStockReserved(String)`),保持传输无关、可脱离中间件单测。
- **差异二(可记录的省略)**:modular-monolith / Eventuate / NServiceBus 都强调入站配 **Inbox / Idempotent Consumer**。本项目 `OrderPlacedListener`、`OrderFulfilment` 这两个 `@EventListener` **未配 inbox**——因其当前是**进程内同步**投递(不重复投递);而**跨进程 Kafka 路径**上 `messaging-kafka` 的 `KafkaIntegrationEventListener` **已用 `Inbox` 按事件 id 去重**(见 [[analysis-00009-saga-implementation-deep-dive]] §五)。故幂等在跨进程传输上补齐、在进程内直投上省略——符合各自语义,但在此点明。

### 命题 D —— "adapter 无理由依赖 domain" 的前提

真正需要 domain 的是**持久化适配器**——它实现 domain 仓储端口并映射聚合:
- `ddd-by-examples-library`:*"repository port interfaces in the domain package, adapters in `infrastructure`, so dependencies point inward."*

本项目已把这些放在 `ordering-infrastructure`。故"入站 adapter 无理由依赖 domain"成立**是这套模块划分的产物**;
若某项目把仓储实现也塞进同一 `adapter` 模块,则该 adapter **有**正当理由依赖 domain。此结论不可泛化为
"任何 adapter 都不该依赖 domain"。

## Consequences

- `ordering-adapter` 去掉对 `ordering-domain` 的依赖(pom + import),入站 web/messaging 两类适配器范式统一。
- 需要一个 **application 层的领域事件订阅示范**(见 [[issue-00001-move-domain-event-listener-to-application]] 方案 1)。
- saga 启动的时序保证不变:领域事件 `OrderPlacedEvent` 仍是进程内、同事务、同步发布,application 订阅者
  在同一事务内启动 saga,先于任何跨上下文响应。
- **已加 ArchUnit 规则固化本决策的分层归位**(`aipersimmon-ddd-archunit` 的 `AiPersimmonDddRules`,均为
  opt-in、不入 `all()`,已在 `multi-module/start/…/ArchitectureTest` 挂上):
  - `domainEventListenersShouldResideInApplicationOrDomain()` —— `@EventListener` 且参数为 `DomainEvent`
    的方法须声明在 `..application..` 或 `..domain..`(承接命题 B;`domain/Core` 亦合法故一并允许)。
  - `integrationEventListenersShouldResideInAdapter()` —— `@EventListener` 且参数为 `IntegrationEvent`
    的方法须声明在 `..adapter..`(承接命题 C 的订阅位置)。
  - `orderingAdapterDoesNotDependOnDomain`(样例本地规则)—— `ordering-adapter` 不得依赖 `…ordering.domain`。
  - 三者刻意保持 opt-in:其余脚手架(`modulith`/`microservice`)的领域事件监听器暂仍在 adapter(见
    [[issue-00001-move-domain-event-listener-to-application]] 未做项),若入 `all()` 会连带打挂它们。
    规则以全限定名匹配 `@EventListener`,故对不使用 Spring 的项目为空匹配、自动通过。
- 顺带修正一处既有规则盲点:`domainEventsShouldStayInDomain()` 原只认 `DomainEvent` **接口**,现改为
  **接口或 `@DomainEvent` 注解**任一路径(`.or().areAnnotatedWith(...)`),使"走注解声明领域事件"的路径
  同样被守卫住。

## Sources

内部(蒸馏笔记与既有文档):

- `docs/reference/clean-architecture/20260708161438-ddd-notes.md` —— 依赖向内;领域事件 handler 置于 Core。
- `docs/reference/domain-driven-hexagon/20260708161438-ddd-notes.md` —— 依赖向内;入站适配器映射到 Command/Query;ACL。
- `docs/reference/spring-modulith-with-ddd/20260708161438-ddd-notes.md` —— 订阅者是 application `@Service` + `@ApplicationModuleListener`。
- `docs/reference/ddd-by-examples-library/20260708161438-ddd-notes.md` —— 事件处理器在 `…/application/…`;仓储端口在 domain、实现在 infrastructure。
- `docs/reference/ddd-by-examples-factory/20260708161438-ddd-notes.md` —— 领域事件传播在 app 层。
- `docs/reference/modular-monolith-with-ddd/20260708161438-ddd-notes.md` —— 入站集成事件 → 内部 command;domain 事件进程内 / 集成事件跨模块。
- [[analysis-00002-domain-vs-integration-events]]、[[analysis-00009-saga-implementation-deep-dive]]、[[decision-00006-integration-event-transport-selection]]。

外部:

- Alistair Cockburn, *Hexagonal Architecture (Ports & Adapters)*. https://alistair.cockburn.us/hexagonal-architecture/
- Robert C. Martin, *The Clean Architecture*(Dependency Rule). https://blog.cleancoder.com/uncle-bob/2012/08/13/the-clean-architecture.html
- Chris Richardson, microservices.io —— *Messaging / Idempotent Consumer / Transactional Outbox / Saga*. https://microservices.io/patterns/data/saga.html
- Gregor Hohpe & Bobby Woolf, *Enterprise Integration Patterns* —— **Process Manager**(C2 的理论源头)、Messaging Endpoint(C1)。https://www.enterpriseintegrationpatterns.com/patterns/messaging/ProcessManager.html
- Axon Framework Reference —— *Sagas*(`@Saga` / `@SagaEventHandler` / `CommandGateway` / `DeadlineManager`)。https://docs.axoniq.io
- Eventuate Tram Sagas(orchestration saga:反应 reply/event → send 命令)。https://eventuate.io/abouteventuatetram.html
- Kamil Grzybek, *Modular Monolith: Integration Styles*(Outbox/Inbox、internal commands)。https://www.kamilgrzybek.com/blog/posts/modular-monolith-integration-styles
- NServiceBus Sagas(`IAmStartedByMessages` / `IHandleMessages` → 发命令)。https://docs.particular.net/nservicebus/sagas/
</content>
