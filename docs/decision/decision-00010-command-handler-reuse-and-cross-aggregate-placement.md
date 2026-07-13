---
id: decision-00010-command-handler-reuse-and-cross-aggregate-placement
type: decision
role: main
status: active
parent:
---

# CommandHandler 不得依赖 CommandHandler;复用逻辑按类型分流与分层落点

固化"当 `CommandHandler` 出现**可复用的编排逻辑**时,该把它抽到哪里、放哪一层、进哪个包"。
承接 [[decision-00005-package-per-aggregate]](领域/应用按聚合、技术层按关注点),
与 [[decision-00008-event-subscriber-layer-placement]](跨聚合走事件 / saga)、
[[decision-00009-event-type-markers-and-handler-contracts]](handler 契约)对齐。每条主张由
`docs/reference/` 笔记原文或外部权威支撑(见 **Sources**)。

## Context

`aipersimmon-ddd-scaffold` 里,`ordering-application/order/` 下的
`ConfirmOrderHandler` 与 `CancelOrderHandler` 编排几乎同构:

```
load order → order.confirm()/cancel() → save → domainEvents.publishAll → clearDomainEvents
```

自然的疑问:既然有"复用的编排逻辑",能不能让一个 handler 复用另一个?但 DDD 的通识是
**CommandHandler 不应依赖另一个 CommandHandler**。于是引出两个必须一起回答的问题:

1. 这条"handler 不调 handler"的规则,依据是什么?
2. 那被复用的逻辑到底该抽成什么、放哪一层、进哪个包?——尤其**跨聚合的领域逻辑**,
   一度被误判为"放 `domain/shared/`"。

## Decision

### 规则一 —— CommandHandler 之间不得互相依赖

`CommandHandler` 是**命令总线上的一个入口点**,不是内部 API。一个 handler 不得注入、构造或调用
另一个 handler。理由(非教条,有具体代价):

- **横切被双重应用或被绕过**。总线上每个 handler 会被 `CommandInterceptor` 包上事务 / 校验 / 日志
  (见 [[decision-00009-event-type-markers-and-handler-contracts]] 与 `CommandBus`)。直接调用对方 handler
  等于**绕过对方整条 interceptor 链**(事务、校验都没了);若改走总线嵌套,又变成**嵌套事务 / 双重日志**。
- **Command 是入口契约,不是函数参数**。直接调用要先"伪造"一个对方的 `Command` 对象,把本应是外部输入
  契约的东西当内部调用参数,污染语义。
- **事务边界含糊**。一次业务操作到底是一个 UoW 还是两个?`modular-monolith-with-ddd` 把 UnitOfWork 定义为
  "围绕单个 command handler 的 decorator",嵌套调用直接破坏该模型。
- **耦合两个入口点**:两个 use case 从此必须一起改、一起测。

### 规则二 —— 复用逻辑按"它到底是什么"分流

不要问"能不能复用 handler",要问"这段被复用的东西是哪一类",再放到正确位置:

| 复用的东西 | 抽成 | 分层 | 包归属 |
| --- | --- | --- | --- |
| 横切关注点(日志 / 事务 / 校验 / 事件抽取发布) | 不属于 handler | —— | 已由 `CommandBus` + `CommandInterceptor` / `AggregateCollector` 提供,handler 里**不重复写** |
| 一段可复用的**应用编排片段**(碰 Port / 仓储 / 事件发布) | 非-handler 的 application collaborator(不实现 `CommandHandler`、无 `Command`、不上总线) | application | 跟用它的用例同包:`application/<usecase>/`,与 handler 并排 |
| **单聚合**的领域规则 | 聚合根方法,或该聚合的 Policy / Specification | domain | 该聚合包 `domain/<aggregate>/` |
| **跨聚合**的领域规则(纯计算、无主) | Domain Service | domain | 见规则三——**不进 `shared/`** |
| 一致性 / 长流程(命令 A 完成后触发 B) | 领域事件 / saga(process manager) | application | 事件订阅按 [[decision-00008-event-subscriber-layer-placement]];saga 在 `application/<process>/` |

判据口诀:**是否 import 了仓储 Port 或事件发布?** 碰了 → 应用编排(应用层);只 import domain 类型的纯计算 → 领域层。

**`ConfirmOrder`/`CancelOrder` 的那点相似属于"用例编排模板",刻意保留、不 DRY。**
两者是各自独立的 use case;把三行骨架抽成公共基类会把本应独立演进的用例焊死(明天 Confirm 加库存校验、
Cancel 加退款,共享骨架立即分叉)。`ddd-by-examples-factory` 明言应用服务就该薄到 "load → one domain method → save"
——薄到只剩模板时,复用收益极小而耦合代价真实。

### 规则三 —— 跨聚合 domain service 的落点:不进 `shared/`

`shared`/`commons`(shared kernel)在参考实现里**只装基类型与共享 VO**,不装领域行为。跨聚合 domain service
的落点按以下优先级:

1. **优先锚回"做决定"的那个聚合**,做成 Policy / Specification 放进该聚合包。多数"看似跨聚合"的规则,
   其实有唯一的决策归属方,另一个聚合只是只读入参。——这能消掉大部分假性跨聚合 service。
2. **若是一致性 / 流程** → 领域事件或 saga(应用层),不是 domain service。
3. **确实无主的纯计算** → 领域层里**按领域概念另起一个包**(如 `domain/pricing/`、`domain/allocation/`),
   与聚合包平级;用 jMolecules `@DomainService` 标识身份(身份靠 marker,与目录正交)。
   一个"无主"的领域概念往往在提示**缺了一个聚合或子域**。

```
com/example/ordering/domain/
├── order/       ← 聚合
├── customer/    ← 聚合
├── shared/      ← 只放共享 VO(Money);不放 service
└── pricing/     ← 真正无主的跨聚合 domain service:按领域概念命名,平级于聚合包
```

## Rationale & sources

### 规则一:handler 不调 handler

| 参考 | 原文 |
| --- | --- |
| `domain-driven-hexagon` | *"**Application Service (= Command Handler)**: orchestrates one use case, no domain logic itself; loads aggregates via Ports, invokes domain behavior, triggers side-effects via Ports; **shouldn't call other application services**."* |
| `modular-monolith-with-ddd` | *"Cross-cutting concerns are **decorators around each command handler**, applied in order: Logging → Validation → UnitOfWork (commit + dispatch domain events)."*(每个 handler 是被 decorator 包裹的独立 UoW 入口) |

### 规则二:复用按类型分流

- **横切归总线**:`modular-monolith-with-ddd` 的 Logging → Validation → UnitOfWork decorator 链;本项目落为
  `CommandBus` + `CommandInterceptor` + `AggregateCollector`(事件抽取)。
- **应用编排片段是薄应用服务**:`ddd-by-examples-factory` — *"Keep application services thin (load → one domain method → save); push rules into aggregates + injected policy objects."*;
  *"Application Service (primary port) — `DemandService` is thin: load aggregate → call one domain method → save."*
- **一致性 / 长流程走事件与 saga**:`domain-driven-hexagon` — *"cross-aggregate consistency via **Domain Events** within a transaction, not direct references."*;
  `axon-framework` — *"Borrow **sagas/process managers** for long-running cross-aggregate workflows."*(并见 [[decision-00008-event-subscriber-layer-placement]])。

### 规则三:跨聚合 domain service 不进 `shared/`

**证据 A —— shared kernel 只装基类型 / VO**

| 参考 | 原文 | shared 内容 |
| --- | --- | --- |
| `ddd-by-examples-library` | *"Shared kernel in `commons` (`aggregates/Version`, `AggregateRootIsStale`, `commands/Result`, `events/...`)."* | 基类型 / 框架件 |
| `ddd-by-examples-factory` | *"`DailyId`, `RefNoId` as identifier VOs in the shared kernel."* | 标识符 VO |

两者 shared kernel 里**没有一个 domain service**;本项目 `domain/shared/` 现装 `Money`,同样是共享 VO 的桶。

**证据 B —— 跨聚合规则优先锚回聚合(Policy/Specification)**

- `ddd-by-examples-library`:*"Domain Policy / Specification — `lending/patron/model/PlacingOnHoldPolicy.java` is a `Function3<AvailableBook, Patron, HoldDuration, Either<Rejection, Allowance>>`; each business rule is a lambda constant..."*
  ——它**读了两个聚合**(`AvailableBook` + `Patron`),却放在 **Patron 聚合包内**,因为决策归属方是 Patron,`AvailableBook` 只是只读入参。参考实现没有为它另立跨聚合 service。

**证据 C —— 真无主的 domain service 是领域层一等公民,身份靠 marker**

- `domain-driven-hexagon`:打包为 vertical-slice by use case,而*"shared domain objects + repositories at **module root**"* / *"shared `domain`/`infrastructure` at module root (Common Closure Principle)"*——共享领域对象在 **domain 根**(与聚合包平级),不是某个叫 `shared` 的子包。
- `jmolecules`:*"`@DomainServiceRing`, `@ApplicationServiceRing`, `@InfrastructureRing`"* —— domain service 靠**注解 ring 标识身份**,与它放哪个目录正交。

**证据 D —— 大厂 / 经典**

- **Eric Evans, _DDD_ "Services"**:当一个重要的领域过程 / 转换不天然属于某个 Entity 或 VO 时,把它建模成一个**独立的领域层 Service**——领域服务属于 domain layer,不是杂物包。
- **Vaughn Vernon, _IDDD_**:domain service 置于领域层,命名取自 Ubiquitous Language(即按领域概念命名,与规则三③一致)。
- **Alibaba COLA / "An In-Depth Understanding of Aggregation in DDD"**:领域层按聚合组织,领域服务是领域层公民(同一来源已支撑 [[decision-00005-package-per-aggregate]])。
- **Martin Fowler, _AnemicDomainModel_**:反面警示——不要把本属聚合的行为无脑外抽成 service;这也是规则三"优先锚回聚合"的动机。

## Consequences

- `ConfirmOrderHandler` / `CancelOrderHandler` **维持现状**(可接受的用例编排模板重复),不抽公共基类。
- 新增复用需求时按规则二决策树分流;跨聚合领域行为按规则三优先级落点,**`domain/shared/` 仅限共享 VO**。
- 与既有决策衔接:一致性 / 长流程复用统一走 [[decision-00008-event-subscriber-layer-placement]] 的事件 / saga,
  不通过 handler 互调实现。
- **规则一已机器固化**:`aipersimmon-ddd-archunit` 的 `AiPersimmonDddRules` 已新增
  `commandHandlersShouldNotDependOnOtherCommandHandlers()`(实现 `CommandHandler` 的类不得依赖其它
  `CommandHandler` 实现)并**并入 `all()`**,与 [[decision-00009-event-type-markers-and-handler-contracts]]
  的 handler 规则同批维护。落地详情见 [[issue-00004-enforce-no-command-handler-to-command-handler-dependency]]。
- 规则二 / 规则三(复用分流、跨聚合落点)是**打包与设计约定**,靠 review 与本决策把关,暂不设 ArchUnit
  强制("应用编排片段 vs domain service"的判定依赖语义,非结构可判)。

## Sources

内部(蒸馏笔记与既有文档):

- `docs/reference/domain-driven-hexagon/20260708161438-ddd-notes.md` —— Command Handler 不调其它应用服务;跨聚合一致性走领域事件;共享领域对象在 module root。
- `docs/reference/ddd-by-examples-factory/20260708161438-ddd-notes.md` —— 应用服务薄(load → one domain method → save);标识符 VO 在 shared kernel。
- `docs/reference/ddd-by-examples-library/20260708161438-ddd-notes.md` —— Policy/Specification 锚在聚合包;shared kernel(`commons`)只装基类型。
- `docs/reference/jmolecules/20260708161438-ddd-notes.md` —— `@DomainServiceRing` 等 ring 注解,身份与目录正交。
- `docs/reference/axon-framework/20260708161438-ddd-notes.md` —— 长流程跨聚合用 saga / process manager。
- `docs/reference/modular-monolith-with-ddd/20260708161438-ddd-notes.md` —— 横切是每个 command handler 外的 decorator(Logging/Validation/UnitOfWork)。
- [[decision-00005-package-per-aggregate]]、[[decision-00008-event-subscriber-layer-placement]]、[[decision-00009-event-type-markers-and-handler-contracts]]。

外部:

- Eric Evans, _Domain-Driven Design_ (2003) —— "Services"(领域服务属领域层)、"Modules"。
- Vaughn Vernon, _Implementing Domain-Driven Design_ —— Domain Services 置于领域层、按 Ubiquitous Language 命名。
- Martin Fowler, bliki — _AnemicDomainModel_. https://martinfowler.com/bliki/AnemicDomainModel.html
- Alibaba Cloud — An In-Depth Understanding of Aggregation in DDD. https://www.alibabacloud.com/blog/an-in-depth-understanding-of-aggregation-in-domain-driven-design_598034
- Alibaba COLA. https://github.com/alibaba/COLA
