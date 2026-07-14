---
id: decision-00013-command-context-and-causation-propagation
type: decision
role: main
status: active
parent:
---

# 命令派发上下文与因果传播:`CommandContext` + 全链路 `EventEnvelope`

固化"消息元数据(message id / correlation / causation / trace)如何从入站集成事件 → 命令 → 出站集成事件
显式传播,以及入站集成事件如何被消费/翻译(ACL)"。承接
[[decision-00009-event-type-markers-and-handler-contracts]](集成事件消费落在 adapter、翻译成 command)、
[[decision-00011-cqrs-write-contracts-as-interfaces-not-annotations]](元数据不进 payload)、
[[decision-00012-no-ambient-per-command-state]](每命令状态必须显式、禁 ambient)。

## 结论先行

> **引入一个显式的 `CommandContext`(`messageId` / `correlationId` / `causationId` / `traceId`),随命令派发
> 一路传递、绝不进入命令 payload。`CommandHandler.handle(C, CommandContext)`、`CommandBus.send(cmd)`(根)
> 与 `send(cmd, cause)`(派生子上下文)、`CommandInterceptor.intercept(cmd, ctx, next)` 全部显式携带它;
> 出站 `IntegrationEvents.publish(event, context)` 据此把 correlation/causation 盖到 `EventEnvelope` 上,
> 经 outbox 行、Kafka header 全链路传播。入站侧不再把集成事件洗成裸 payload,而是把完整
> `EventEnvelope<E>` 交给入站 adapter(ACL),adapter 把它映射成 `CommandContext` 再派发命令。**

## Context

`docs/reference` 语料对"因果元数据"支撑很弱:全语料没有 payload+metadata 的 Message 抽象,`causationId`/
`messageId`/`traceId` 一次都没出现;`correlation` 只在 modular-monolith 的**日志装饰器**里出现一次
(*"Cross-cutting concerns are decorators around each command handler … Logging (correlation IDs)"*)。
语料强力支撑的是**边界形态**:ACL = 入站 adapter+mapper → 同一 Command(domain-driven-hexagon:*"Multiple
entry adapters per use case … each mapping to the same Command/Query"*、*"the Adapter … also the
Anti-Corruption Layer"*);**Inbox → internal command**(modular-monolith:*"a worker … runs an internal
command per event"*)。

因此:**完整因果链(causation graph)是"大厂框架实践"(Axon `Message`/`ProcessingContext`、NServiceBus
`IMessageHandlerContext`),不是本仓语料的主张**——本决策按用户"参考 docs/reference 与大厂最佳实践、不追求
最小改动、有问题就改"的要求,在语料支撑的边界形态之上,采纳成熟框架的显式因果传播。

改造前的两处实证问题(见调研):(1)入站 `KafkaIntegrationEventListener` 读完 header 后
`publishEvent(裸 payload)`,`VERSION/OCCURRED_AT/TRACE_ID` 收到却丢弃,ACL adapter 拿不到任何元数据;
(2)`traceId` 全链路定义了却在两个 OutboxWriter 都写死 `null`——死字段。

## Decision

1. **`CommandContext`(`aipersimmon-ddd-cqrs`,framework-free、纯值)**:`messageId`、`correlationId`
   (根命令等于自身 messageId)、`causationId`(触发它的上一条消息 id,根命令为 `null`)、`traceId`(可空)。
   id 由 bus 铸造(`root(id, trace)` / `deriveChild(childId)`),`CommandContext` 自身不生成 id,保持纯值。
2. **写侧契约全部显式携带 context**:`CommandHandler.handle(C, CommandContext)`;`CommandBus` 双重载——
   `send(cmd)`(根,bus 读 MDC `traceId` 播种)与 `send(cmd, cause)`(以 cause 派生子上下文:新 messageId、
   继承 correlation/trace、causation = cause.messageId);`CommandInterceptor.intercept(cmd, ctx, next)`。
   命令 payload **不含**任何 correlation/causation 字段(承接 [[decision-00011-cqrs-write-contracts-as-interfaces-not-annotations]])。
3. **出站显式盖章**:`IntegrationEvents.publish(event, CommandContext)`;`EventEnvelope` 增
   `correlationId`(必填)、`causationId`(可空);`OutboxMessage` / outbox 表 / `IntegrationEventHeaders`
   同步新增两字段两 header。发布时 `correlationId = ctx.correlationId`、`causationId = ctx.messageId`。
4. **入站交付完整信封**:`KafkaIntegrationEventListener`、`InProcessOutboxDispatcher`、`SpringIntegrationEvents`
   一律重建 `EventEnvelope<E>` 并经 Spring `PayloadApplicationEvent`(携带 payload 泛型 `ResolvableType`)发布,
   使入站 adapter 用 `@EventListener void on(EventEnvelope<E>)` 收到**体+元数据**。adapter(ACL)用**唯一的
   共享工厂** `CommandContext.of(envelope)`(与 `root`/`deriveChild` 同在 `CommandContext` 上)把信封映射成
   触发上下文,再 `commandBus.send(cmd, CommandContext.of(envelope))`——每个 adapter **不再各写一份**转换。
5. **不新增 `IntegrationEventHandler` 契约**(decision-00009 命题 4/5 不变):入站 adapter 仍是普通 Spring
   `@EventListener` 方法,只是收 `EventEnvelope<E>` 而非裸 payload;`EventEnvelope` 是**传输信封**、不是
   handler marker。集成事件性仍止步于 adapter。
6. **模块依赖**:`aipersimmon-ddd-application` 依赖 `aipersimmon-ddd-cqrs`(`IntegrationEvents.publish` 需
   `CommandContext`);`aipersimmon-ddd-cqrs` 依赖 `aipersimmon-ddd-integration`(`CommandContext.of(EventEnvelope)`
   这一唯一的入站转换工厂需 `EventEnvelope`)。两条都在 framework-free 纯契约层之间,`integration` 是依赖汇点
   (只依赖 core),故整体无环:`integration ← cqrs ← application`。

## Rationale

### 命题一 —— 因果传播必须显式,不能 ambient(与 decision-00012 同源)

handler 在方法体里 publish 的出站事件要盖因果戳,就必须让 handler 拿到上下文——要么改 `handle` 签名让 context
作为显式参数,要么用 ThreadLocal/ambient(已被 [[decision-00012-no-ambient-per-command-state]] 禁止)。没有第三条路。
故选**显式参数**:`handle(C, CommandContext)`。这正是 Axon 5 用显式 `ProcessingContext` 取代线程绑定 `UnitOfWork`
的同一取向——为响应式/虚拟线程去除 ThreadLocal 依赖。

### 命题二 —— 元数据在 payload 之外(与 decision-00011 同源)

命令 payload 保持纯业务字段;correlation/causation 作为 `CommandContext` 在命令**旁边**传递,`EventEnvelope`
在消息**旁边**携带。这与 Axon 的 `Message = payload + metadata`、NServiceBus 的 `Handle(msg, context)` 一致,
也与本仓 [[decision-00011-cqrs-write-contracts-as-interfaces-not-annotations]]"派发契约与标签分离"一脉相承。

### 命题三 —— 入站交付完整信封,是 ACL 的必要条件(语料强支撑)

domain-driven-hexagon 明确 adapter 即反腐层,把外部消息翻译成本地 Command。反腐层要正确翻译,必须能看到**完整
外部消息**(头+体)。改造前的"洗成裸 payload"结构性地剥夺了 adapter 的元数据;交付 `EventEnvelope<E>` 修复之。
用 `PayloadApplicationEvent` 携带 payload 泛型解决 Spring 事件的类型擦除,使 `EventEnvelope` 保持 framework-free
(不 `implements ResolvableTypeProvider`,Spring 依赖只落在发布方,发布方本就在 Spring 模块)。

### 命题四 —— 不为 1:N/void 的入站消费新造 handler 契约(decision-00009 不翻)

集成事件消费是 adapter+消息框架的传输关注点;adapter 收 `EventEnvelope<E>` 的普通 `@EventListener` 方法已足够
自证身份(所在层 + 框架注解 + 引用 `IntegrationEvent`/信封类型)。新增库级 `IntegrationEventHandler` 契约边际≈0,
与 [[decision-00009-event-type-markers-and-handler-contracts]] 命题 4/5 冲突且无收益,故不做。

## Consequences

- 写侧:新增 `CommandContext`;`CommandBus`/`CommandHandler`/`CommandInterceptor` 及全部内置拦截器、
  `RegistryCommandBus` 均改为携带 context;`LoggingCommandInterceptor` 把 `correlationId` 放入 MDC。
- 契约:`EventEnvelope`、`OutboxMessage`、outbox DDL(jdbc + mybatis-plus)、`IntegrationEventHeaders`
  新增 `correlationId`/`causationId`;`IntegrationEvents.publish` 增 `CommandContext` 参数。
- 入站:kafka listener / in-process 派发 / spring 发布器统一投递 `EventEnvelope<E>`;adapter 收信封,经**唯一**
  的 `CommandContext.of(EventEnvelope)` 工厂映射(不再每个 adapter 各写一份 `causeOf`——最初内联导致 6 处重复,
  现已收敛)。
- 依赖:`application → cqrs`、`cqrs → integration`(见 Decision §6);均为纯契约层之间、无环。
- scaffold(multi-module / modulith / microservice)全部随之迁移;`OrderingFlowTest` 端到端验证跨上下文
  saga 流程 + 信封路由 + 因果传播全绿。
- 与 [[decision-00009-event-type-markers-and-handler-contracts]] 一致:不新增集成事件处理器契约;
  `DomainEventHandler` 的 Javadoc"集成侧无对等 marker"依然成立(`EventEnvelope` 是传输信封,非 handler marker)。

## Sources

内部:

- `docs/reference/domain-driven-hexagon/20260708161438-ddd-notes.md` —— adapter 即 ACL;多入站 adapter 映射到同一 Command/Query。
- `docs/reference/modular-monolith-with-ddd/20260708161438-ddd-notes.md` —— 日志装饰器承载 correlation id;inbox → internal command。
- `docs/reference/axon-framework/20260708161438-ddd-notes.md` —— 消息三分(command/event/query)的基础。
- `aipersimmon-ddd/aipersimmon-ddd-cqrs/.../CommandContext.java`、`.../CommandBus.java`、`.../CommandHandler.java`。
- `aipersimmon-ddd/aipersimmon-ddd-integration/.../EventEnvelope.java`;`aipersimmon-ddd-messaging-kafka/.../KafkaIntegrationEventListener.java`。
- [[decision-00009-event-type-markers-and-handler-contracts]]、[[decision-00011-cqrs-write-contracts-as-interfaces-not-annotations]]、[[decision-00012-no-ambient-per-command-state]]、[[analysis-00002-domain-vs-integration-events]]。

外部:

- Axon Framework —— `Message`(payload + metadata)、`CommandMessage`/`EventMessage`、以显式 `ProcessingContext` 取代线程绑定 UnitOfWork(correlation/causation 自处理上下文传播)。https://docs.axoniq.io
- NServiceBus —— `Handle(message, IMessageHandlerContext)` + `context.Send(...)`;框架自动设置 `CorrelationId` / `RelatedTo`(causation)header。https://docs.particular.net
- Gregor Hohpe & Bobby Woolf, *Enterprise Integration Patterns* —— Correlation Identifier、Message metadata。https://www.enterpriseintegrationpatterns.com
