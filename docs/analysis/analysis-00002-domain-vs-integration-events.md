---
id: analysis-00002-domain-vs-integration-events
type: analysis
role: main
status: active
parent:
---

# 领域事件 vs 集成事件：要不要区分？怎么落地？

回答一个常见的架构争论：**Domain Event 和 Integration Event 真的需要区分吗？有没有大厂最佳实践？**
本分析给出判定轴、行业依据，以及落到本模板（`lang/java/ddd`）的务实建议。

配套阅读：[[analysis-00001-domain-event-publishing]]（领域事件的发布/消费机制与可插拔 publisher 实现）。

## 结论先行

> **需要区分的是"契约稳定性"，不是"两个 class"。**
> 是否要落成两种类型，取决于生产者和消费者**是否共享部署 / 版本生命周期**。

- 概念上**永远区分**：领域事件是内部实现细节，集成事件是对外发布的**契约**。
- 物理上**按边界决定**：同一可部署单体内 → 可复用同一事件（概念区分即可）；跨网络 / 独立部署 → 必须两套类型 + 版本化契约。
- 判定分界线一句话：**能不能一次编译抓到所有下游**。能 → 不拆；不能 → 拆。
- **不要预先拆**：没有第二个独立生命周期的消费者时，提前造两套类型是 YAGNI。

## 一、为什么概念上必须区分

两者解决不同问题：

| | 领域事件 (Domain Event) | 集成事件 (Integration Event) |
| --- | --- | --- |
| 面向谁 | **本上下文内部**——模型与通用语言的一部分 | **别的上下文 / 服务**——对外发布的**契约** |
| 稳定性 | 实现细节，可随聚合重构**自由改** | 一旦发布须**向后兼容、版本化**；改它 = 破坏下游 |
| 内容 | 可携带内部类型、丰富上下文 | 只带 **ID + 最小必要数据**，不暴露内部类型 |
| 投递 | 进程内，常同事务，可强一致 | 异步、最终一致、at-least-once、消费方幂等 |

**核心理由只有一个：防腐 / 解耦演化。** 若把领域事件**原样**发到边界外，每个消费者都耦合到你的内部模型；
你一重构聚合，下游全炸。集成事件本质是一层 **ACL（防腐层）/ Published Language**——
把"内部模型怎么变"与"对外承诺什么"隔开。

## 二、要不要落成两个类型？看耦合轴

别一上来无脑拆。真正的分界是"**能否一次编译抓到所有下游**"：

- **模块化单体、进程内、同步部署** → 通常**可复用同一个领域事件**。编译器能一次性抓到所有破坏点，
  重构原子；拆两套类型的成本收不回。**Spring Modulith 的默认哲学正是如此**：内部跨模块直接传领域事件，
  只有**外发到 broker 时才 `mapping()` 翻译**成对外 payload。
- **跨网络 / 独立服务 / 独立发布节奏** → **必须拆**。此时消费者与你不同步上线，
  wire format、schema 演化、版本兼容都成硬约束，领域事件绝不能当契约。

## 三、大厂 / 权威实践

- **Microsoft —— eShopOnContainers + 官方《.NET Microservices: Architecture for Containerized .NET Applications》**：
  最具体、最常被引用的落地样本。明确分两层：`DomainEvent`（进程内，MediatR，同一事务 / scope）
  vs `IntegrationEvent`（跨服务，走 event bus / RabbitMQ / Azure Service Bus，最终一致），
  并配 `IntegrationEventLog` = **Outbox**。想看代码范本首选这个。
- **Kamil Grzybek —— modular-monolith-with-ddd**（见 `docs/reference/modular-monolith-with-ddd/`）：
  同样两级 + Outbox/Inbox，单体内的权威范例。
- **Confluent / Kafka 生态**：把"**事件即 API / 契约**"做到极致——Schema Registry + Avro/Protobuf +
  向后兼容策略。本质就是在大规模贯彻"集成事件是版本化契约"，只是不用 DDD 术语。
- **Martin Fowler**：区分 *Event Notification*（瘦事件，只带 ID）vs
  *Event-Carried State Transfer*（带状态）——直接指导"集成事件该塞多少数据"。
- **Netflix / Uber 等大规模事件驱动**：实践上是 **contract-first + schema registry**，
  与其说区分"领域 / 集成事件"，不如说把**跨服务事件当强契约**管理——殊途同归。

## 四、各 reference 的立场（对照本模板的 8 个参考）

| 项目 | 是否显式区分两类事件 | 机制 |
| --- | --- | --- |
| modular-monolith-with-ddd | ✅ 类型层面分两级 | 手写 Outbox + Inbox；独立 IntegrationEvents 契约程序集 |
| spring-modulith-with-ddd | 部分：进程内复用领域事件，**外发时**才翻译 | `EventExternalizationConfiguration.mapping()` + `@Externalized` |
| ddd-by-examples-library | ❌ 只有领域事件 | store-and-forward publisher（见 [[analysis-00001-domain-event-publishing]]） |
| ddd-by-examples-factory | ❌ 仅进程内同步领域事件 | `DemandEventsPropagation` 同步扇出，无 broker |
| axon-framework | 事件即真相源（ES） | `EventBus` / `EventStore`；跨界另配 |
| 其余（clean-architecture / domain-driven-hexagon / jmolecules） | ❌ 进程内领域事件为主 | 无集成事件 / outbox 故事 |

> 注：Spring Modulith 框架**支持** DomainEvent→IntegrationEvent 翻译（`mapping()`），
> 但 `spring-modulith-with-ddd` 那个参考项目是 in-process、无 broker，故未使用该能力。
> 框架能力 ≠ 该 repo 用到——勿混淆。

## 五、落到本模板的务实建议

1. **概念上永远区分**，写进架构约定文档。
2. **物理上按边界决定**：
   - 跨模块、同一可部署单体 → 用 Spring Modulith，内部复用领域事件，
     只在 `@Externalized` + `mapping()` 外发时翻译成集成事件；
   - 一旦某消费方要独立部署 / 异构语言 → 为它定义显式集成事件契约 + 版本化。
3. **别预先拆**：没有独立生命周期的第二个消费者时，两套类型是 YAGNI。
4. **跨进程一定配 Outbox** 保证不丢（发布机制见 [[analysis-00001-domain-event-publishing]]）。
5. 集成事件内容遵循 Fowler：默认瘦事件（ID + 最小数据），确需减少回查时才升级为 event-carried state transfer。

## 相关参考

- [[analysis-00001-domain-event-publishing]] —— 发布 / 消费机制与可插拔 publisher 实现。
- `docs/reference/modular-monolith-with-ddd/` —— 两级事件 + Outbox/Inbox。
- `docs/reference/spring-modulith-with-ddd/` —— 事件外化 `mapping()` + `@Externalized`。
