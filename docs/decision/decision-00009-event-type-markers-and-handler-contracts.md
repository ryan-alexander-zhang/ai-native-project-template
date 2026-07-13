---
id: decision-00009-event-type-markers-and-handler-contracts
type: decision
role: main
status: active
parent:
---

# 事件类型标记与三种 Handler 的契约形态

固化"事件用什么**类型标记**、以及领域事件/集成事件的**处理器**各是什么契约、放哪一层"。
承接 [[decision-00008-event-subscriber-layer-placement]](订阅者的分层归属)与
[[analysis-00002-domain-vs-integration-events]](为何区分两类事件),并回答"要不要照搬
jMolecules 的 `jmolecules-events` 那套标记"。每条主张由 `docs/reference/` 原文支撑(见 **Sources**)。

## 结论先行

> **两个独立事件类型标记 `DomainEvent` + `IntegrationEvent`(不采用 jMolecules 的单事件
> `@Externalized` 模型);`@DomainEventHandler` 作为【必须】的架构语义注解;不提供
> `@IntegrationEventHandler`——集成事件消费是 adapter 层的事,翻译成 command 后由普通 CQRS
> `CommandHandler` 处理,处理器不体现"集成事件"身份。**

## Context

`jmolecules-events` 提供一整套:*"annotations `@DomainEvent`, `@DomainEventPublisher`,
`@DomainEventHandler`, `@Externalized`; interfaces `DomainEvent`, `Externalized`."*
问题:本库该照搬哪些?尤其三点——

1. 对外事件用 jMolecules 的 **`@Externalized`(单事件加标记)**,还是**独立的 `IntegrationEvent` 类型**?
2. 领域事件处理器要不要一个 **`@DomainEventHandler`** 架构语义注解?
3. 集成事件的处理器要不要 **`@IntegrationEventHandler`**、要不要"体现出是集成事件的 handler",还是就用 CQRS `CommandHandler`?

前提:命令与事件在消息学上**非对称**——`axon-framework`:*"Three message types: **commands
(intent, one handler)**, **events (facts, many** [handlers])…"*。命令 1:1 且有返回值(故
`CommandHandler<C,R>` 类型化物有所值);事件 1:N 且 void(故订阅侧生态惯例是**注解 + 方法**)。

## Decision

1. **两个独立事件类型标记**:`DomainEvent`(内部)与 `IntegrationEvent`(对外契约)两个**独立
   marker 接口**。**不采用** jMolecules 的 `@Externalized`/`Externalized`(单事件加标记)模型。
   领域事件**另有**一个 `core.annotation.@DomainEvent` 角色注解;集成事件**有意不设** `@IntegrationEvent`
   注解(理由见命题一末)。
2. **不提供** `@DomainEventPublisher`:发布走 `DomainEvents` / `IntegrationEvents` **端口**,不用注解标发布方法。
3. **`@DomainEventHandler`——必须**。作为架构语义注解,标注"这是一个领域事件的订阅者"(application 层组件),
   使分层意图显式、自文档化,并让 ArchUnit 能**按注解**精确定位(而非靠命名或反射参数类型)。
4. **不提供** `@IntegrationEventHandler`。集成事件的消费是 **adapter 层 + 消息框架**的事:入站 adapter
   把集成事件**翻译成 command**,进入 application 后由**普通 CQRS `CommandHandler`** 处理。
5. **集成事件的处理器不体现"集成事件"身份**:翻译后的 `CommandHandler` 只是普通 CQRS 处理器;
   "集成事件性"止步于 adapter listener(靠所在层 + 消息框架注解 + 引用 `IntegrationEvent` 类型自证)。

## Rationale

### 命题一 —— 独立 `IntegrationEvent` 类型 优于 jMolecules `@Externalized`

`@Externalized` 与 `IntegrationEvent` 是**两种建模哲学**,非改名:
- `@Externalized`(jMolecules):外部契约 = 内部领域事件本身 + 标记。外部契约与内部模型**耦合**。
- `IntegrationEvent`(独立类型):对外是**独立、版本化**的契约,与内部领域事件解耦,各自演进。

对限界上下文 / 微服务模板,独立类型更合适——集成事件是**版本化契约**(向后兼容演进、破坏性变更升 version),
见 [[analysis-00002-domain-vs-integration-events]]。证据:
- `modular-monolith-with-ddd`:*"A separate **IntegrationEvents** assembly per module publishes only the event contracts others may depend on — never implementation."* / *"A module depends only on other modules' integration-event contracts."*
- `modular-monolith-with-ddd`:*"Application (CQRS handlers, **domain events**, **integration events**, internal commands)"*(二者并列为不同类型)。

**为何集成事件不设 `@IntegrationEvent` 注解(有意的不对称)**:领域事件的 `core.annotation.@DomainEvent`
并不是"`DomainEvent` 接口的注解版",而是 `core.annotation` 里**战术 DDD 角色标记家族**的一员
(`@AggregateRoot`/`@Entity`/`@ValueObject`/`@Repository`/`@Service`/`@Identity`/`@DomainEvent`)——它标记的是
一个**领域建模角色**。集成事件不是领域战术角色,而是**集成层的对外契约**(住在 `aipersimmon-ddd-integration`,
配套 `EventEnvelope`),靠**实现 `IntegrationEvent` 接口**声明即可;集成层没有对应的"战术角色注解家族",
硬加 `@IntegrationEvent` 会错误暗示它是个领域战术角色。加上接口本身已 framework-free、集成事件总是刻意设计的
契约(实现接口是惯用法),注解版收益≈0,且当前无任何规则需按注解识别集成事件。故**只提供 `IntegrationEvent`
接口,不设注解**——这与命题三"集成侧保持精简"(连 `@IntegrationEventHandler` 也不设)同源。

### 命题二 —— `@DomainEventHandler` 必须(架构语义注解)

- **有先例**:`jmolecules`:*"`jmolecules-events`: annotations `@DomainEvent`, `@DomainEventPublisher`, **`@DomainEventHandler`**, `@Externalized`; interfaces `DomainEvent`, `Externalized`."*
- **订阅者是一等的 application 概念**,理应有显式标记——参考一致把领域事件处理器放在 application/Core:
  - `ddd-by-examples-library`:*"Cross-context integration via **event handlers** — `book/application/CreateAvailableBookOnInstanceAddedEventHandler` … `PatronEventsHandler`."*
  - `clean-architecture`:*"handlers co-located in `Core/.../Handlers`"*。
- **可强制性**:注解让 [[decision-00008-event-subscriber-layer-placement]] 的 ArchUnit 规则
  (`domainEventListenersShouldResideInApplicationOrDomain`)按注解精确定位,不依赖命名约定或参数反射。
- **与本库标记哲学一致**:core 已有 `@AggregateRoot` 等角色标记、architecture 已有 `@DomainLayer` 等分层标记;
  `@DomainEventHandler` 是同一路数的"意图显式、工具可读"标记。
- 注解**不锁定实现框架**(不像 Spring `@EventListener`),符合本库 framework-free 契约取向。

> 归属:作为 application 层构建块提供(与 `UseCase`/`DomainEvents` 同侪);注解本身不规定层,
> 由 ArchUnit 规则强制"被标注的处理器须在 application/domain"。

### 命题三 —— 不要 `@IntegrationEventHandler`;集成事件用普通 CQRS `CommandHandler`

集成事件消费落在 adapter,翻译成 command,由普通命令处理器处理(承接 [[decision-00008-event-subscriber-layer-placement]] 命题 C1):
- `modular-monolith-with-ddd`:*"a worker reads unprocessed rows, **runs an internal command per event**"*——处理器是 `ICommandHandler<TCommand>`,**不是**某种 IntegrationEventHandler。
- `domain-driven-hexagon`:*"Multiple entry adapters per use case are normal (http/cli/message/graphql), each **mapping to the same Command/Query**."*

**为何处理器不该体现"集成事件"身份**:同一个 command(如 `ReserveStock`)可能来自集成事件、HTTP、CLI 或测试。
一旦让 `CommandHandler` "体现集成事件",就把**触发来源**泄漏进 application 层,C1"翻译成 command"换来的解耦即失效。
故"集成事件性"只留在 **adapter listener**(所在层 + 消息框架注解 + 引用 `IntegrationEvent` 类型已足够自证),
无需领域/应用层的 `@IntegrationEventHandler` 概念。

### 为何领域事件有注解、集成事件没有(非对称的理由)

- **领域事件处理**是**一等的 application 概念**(上下文内部反应),值得显式标记 → `@DomainEventHandler`。
- **集成事件消费**是**传输/边界关注点**(adapter + 消息框架),其身份由**位置与类型**自证,且下游是普通 command 处理 → 不需要注解。

## Consequences

- 保留/明确 `DomainEvent`(core)与 `IntegrationEvent`(integration)两个 marker 接口;不引入 `@Externalized`、`@DomainEventPublisher`。
- **新增 `@DomainEventHandler` 注解**(application 层构建块),并让相关 ArchUnit 规则按其定位。
- 集成事件入站:adapter listener(引用 `IntegrationEvent`)→ 翻译成 command → 普通 `CommandHandler`;不新增集成事件处理器契约。
- 命名承接 [[decision-00008-event-subscriber-layer-placement]]:领域事件订阅者用 `<动作>On<事件>` 或 `<主题>EventsHandler`,并标注 `@DomainEventHandler`。
- 待办:在库中落地 `@DomainEventHandler`;`AiPersimmonDddRules` 增加/对齐按注解判定的规则。

## Sources

内部:

- `docs/reference/jmolecules/20260708161438-ddd-notes.md` —— `jmolecules-events` 的 `@DomainEvent`/`@DomainEventPublisher`/`@DomainEventHandler`/`@Externalized`。
- `docs/reference/modular-monolith-with-ddd/20260708161438-ddd-notes.md` —— 独立 IntegrationEvents 契约;inbox→internal command;domain vs integration events 并列。
- `docs/reference/domain-driven-hexagon/20260708161438-ddd-notes.md` —— 入站适配器映射到同一 Command/Query。
- `docs/reference/ddd-by-examples-library/20260708161438-ddd-notes.md` —— 事件处理器置于 `…/application/…`。
- `docs/reference/clean-architecture/20260708161438-ddd-notes.md` —— handlers 置于 Core/Handlers。
- `docs/reference/axon-framework/20260708161438-ddd-notes.md` —— "commands (one handler), events (many)" 非对称。
- [[decision-00008-event-subscriber-layer-placement]]、[[analysis-00002-domain-vs-integration-events]]、[[analysis-00009-saga-implementation-deep-dive]]。

外部:

- jMolecules —— `jmolecules-events`(`@DomainEvent` / `@DomainEventHandler` / `@Externalized`)。https://github.com/xmolecules/jmolecules
- Gregor Hohpe & Bobby Woolf, *Enterprise Integration Patterns* —— Event Message / Command Message / Process Manager。https://www.enterpriseintegrationpatterns.com
- Kamil Grzybek, *Modular Monolith: Integration Styles*(integration events 为独立契约;inbox → internal command)。https://www.kamilgrzybek.com/blog/posts/modular-monolith-integration-styles
- Axon Framework Reference —— 消息三分(command 单处理器 / event 多处理器)。https://docs.axoniq.io
- Vaughn Vernon, *Implementing Domain-Driven Design* —— domain event 与 published language 的区分。
</content>
