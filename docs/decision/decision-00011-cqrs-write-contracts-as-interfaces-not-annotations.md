---
id: decision-00011-cqrs-write-contracts-as-interfaces-not-annotations
type: decision
role: main
status: active
parent:
---

# CQRS 写侧契约用接口、查询侧标记用注解:不提供 `@Command`

固化"`Command` / `CommandHandler` 该做成**接口**还是像 jMolecules `ddd-core` 那样再配一个**语义注解**"。
承接 [[decision-00009-event-type-markers-and-handler-contracts]](事件侧"哪些用接口、哪些用注解"的同类抉择)
与 [[decision-00010-command-handler-reuse-and-cross-aggregate-placement]](handler 是命令总线上的入口点),
并回答"要不要照搬 jMolecules `jmolecules-cqrs-architecture` 的 `@Command` 标记"。每条主张由库内既有设计与
`docs/reference/` 原文支撑(见 **Sources**)。

## 结论先行

> **写侧派发契约(`Command<R>`、`CommandHandler<C,R>`、`CommandBus`、`CommandInterceptor`)一律用【接口】,
> 【不提供】`@Command` / `@CommandHandler` 语义注解。判据:只做身份标签 → 注解;参与带类型的派发契约 → 接口。
> `Command<R>` 的类型参数 `<R>` 是承重的,注解无法表达,因此注解不能替代接口,只会冗余叠加。查询侧的纯标记
> (`@Projection`、`@ReadModel`)保持注解——这条区分律本库在同一个 cqrs 包内已经在用。**

## Context

`jmolecules-cqrs-architecture` 提供一套 CQRS 风格标记:*"`@Command`, `@CommandDispatcher`,
`@CommandHandler`, `@QueryModel`."*——全是**纯 marker 注解、零类型信息**。问题:本库写侧已把
`Command` / `CommandHandler` 做成接口,是否应当**照搬 jMolecules,额外提供一个 `@Command` 注解**来"标示语义"?

前提是命令的消息学特征:`axon-framework`:*"Three message types: **commands (intent, one handler)**,
events (facts, many)…"*——命令 **1:1 且有返回值**。本库据此把写侧建成一套**静态类型化派发**:

```java
public interface Command<R> {}                                              // R = 结果类型(承重)
public interface CommandBus     { <R> R send(Command<R> command); }         // R 流出 → 编译期结果类型
public interface CommandHandler<C extends Command<R>, R> { R handle(C c); } // command↔handler↔result 三方绑定
record PlaceOrder(...) implements Command<String> {}                        // send(placeOrder) 静态得到 String
```

jMolecules 的 `@Command` 之所以能是注解,正因其 CQRS 派发是**反射式 / 无类型**的,结果类型不在类型系统里表达。
两条路线并非改名,而是两种建模哲学——与 [[decision-00009-event-type-markers-and-handler-contracts]] 中
"`@Externalized` vs 独立 `IntegrationEvent` 类型"是同构的抉择。

## Decision

1. **写侧四件套一律接口**:`Command<R>`、`CommandHandler<C extends Command<R>, R>`、`CommandBus`、
   `CommandInterceptor` 保持接口形态,不改为注解、不追加平行注解。
2. **不提供 `@Command` / `@CommandHandler` 注解**。命令靠 `implements Command<R>` 声明身份,处理器靠
   `implements CommandHandler<C,R>` 声明身份——身份由**类型**承担,且被编译器强制。
3. **查询侧纯标记维持注解**:`@Projection`、`@ReadModel` 继续做注解(只标身份、无派发契约)——本条决定不改变它们。
4. **判据固化**:*只做身份标签(无契约、无类型关系)→ 注解;参与带类型的派发契约(有行为 / 有类型参数)→ 接口。*
   该判据同时解释了本库既有的接口/注解划分(见 Rationale 命题二)。
5. **注解只在"叠加元数据"时才引入**,且叠加在接口之上、不取代接口(见命题三的重开条件)。

## Rationale

### 命题一 —— `Command<R>` 是带类型的契约,不是 marker;注解无法替代

`<R>` 是**承重类型参数**,贯穿三处:`CommandBus.send(Command<R>): R` 让派发结果在**编译期**定型;
`CommandHandler<C extends Command<R>, R>` 把 command、handler、result **三方绑定**。注解**不携带类型参数**,
`@Command` 表达不了 `<R>`。因此:

- 注解**不能替代**接口——去掉接口改注解,会同时废掉 `send()` 的类型安全与 handler 绑定,是**能力降级**。
- 注解只能**冗余叠加**在接口之上(既 `implements Command<String>` 又 `@Command`),这引出命题四的代价。

jMolecules `@Command` 能成立恰因其派发无类型;本库选择了更强的静态派发,照搬 `@Command` 与本库路线相悖。

### 命题二 —— 本库在同一个 cqrs 包内已经在用这条区分律

看库内什么用注解、什么用接口,规律干净且已自洽:

| 形态 | 库内类型 | 判据 |
|---|---|---|
| **注解** | `core.annotation` 的 `@AggregateRoot` `@Entity` `@ValueObject` `@Service` `@Repository` `@Identity` `@DomainEvent`;`application` 的 `@UseCase` `@DomainEventHandler`;**cqrs 的 `@Projection` `@ReadModel`**;architecture 的 `@DomainLayer` 等 | 只做身份 / 分层标签,无契约、无类型关系 |
| **接口** | `Command<R>` `CommandHandler<C,R>` `CommandBus` `CommandInterceptor`;各仓储接口 | 参与带类型的派发契约 / 有行为 |

关键点:**就在 cqrs 这个包内部**,作者已做过同一抉择——查询侧纯标记 `@Projection` / `@ReadModel` 做注解,
写侧派发契约 `Command` / `CommandHandler` 做接口。用户之问("写侧要不要也退回注解")的答案,本库设计律已给出:
**`Command` 属于"参与类型化派发契约"一类 → 接口。** 追加 `@Command` 会破坏这条已在运行的一致性。

### 命题三 —— "注解保持 domain 无框架依赖"这条不适用于命令

事件侧 [[decision-00009-event-type-markers-and-handler-contracts]] 命题一给过 domain 用注解的理由:domain 层
import 什么都不引。但**命令住在 application 层**,其职责本就是依赖 CQRS 契约——耦合 `Command<R>` 是 intended,
与 handler `implements CommandHandler` 同理。这里没有需要保护的"领域纯净度",故"注解=零耦合"的动机在写侧落空。

### 命题四 —— 追加 `@Command` 是纯成本

- **双真相源**:命令须同时 `implements Command<String>` + `@Command`,二者会漂移,还得再写 ArchUnit 规则校验
  一致性——纯维护成本、零能力增益。
- **可发现性本就够且更强**:`implements Command<String>` 至少与 `@Command` 一样自解释,且是**编译器强制**的
  (`send()` 不了非 `Command`);被动注解做不到。工具侧也已够用——`AiPersimmonDddRules` 的
  `commandHandlersShouldNotDependOnOtherCommandHandlers`(见 [[decision-00010-command-handler-reuse-and-cross-aggregate-placement]])
  正是靠 `CommandHandler` **接口**识别 handler,无需注解。

### 何时重开(引入 `@Command` 的正当条件)

仅当出现具体需求,且均为**叠加元数据、不取代接口**:

1. 决定拥抱 jMolecules,让 **jQAssistant / jMolecules-ArchUnit / ByteBuddy** 跨库识别命令;
2. 命令载荷需**零 lib import**(作为纯 record 跨边界共享)并改用**反射派发**——此时才回到 jMolecules 的无类型模型;
3. 需要接口表达不了的**属性型元数据**,如 `@Command(idempotent = true, timeout = ...)`——这是注解的正当用途,
   叠加在接口之上。

三者当前都不成立,故现在加 `@Command` 属投机性设计。

## Consequences

- 写侧契约维持接口现状,**不新增** `@Command` / `@CommandHandler`;命令 / 处理器身份继续由类型承担。
- 查询侧 `@Projection` / `@ReadModel` 维持注解不变。
- 判据"标签→注解、类型化契约→接口"可作为库内后续"接口 vs 注解"抉择的默认裁决;与
  [[decision-00009-event-type-markers-and-handler-contracts]] 的事件侧非对称结论同源(命令 1:1 有返回值→类型化接口;
  领域事件订阅是一等 application 概念→注解)。
- 若将来满足"重开条件",按叠加元数据方式引入注解,并同步新增按注解判定的 ArchUnit 规则;在此之前无待办。

## Sources

内部:

- `docs/reference/jmolecules/20260708161438-ddd-notes.md` —— `jmolecules-cqrs-architecture` 的
  `@Command` / `@CommandDispatcher` / `@CommandHandler` / `@QueryModel`(纯 marker 注解)。
- `docs/reference/axon-framework/20260708161438-ddd-notes.md` —— 消息三分:command(intent、单处理器、有返回值)
  与 event(facts、多处理器)的非对称,支撑写侧类型化 `CommandHandler<C,R>`。
- `aipersimmon-ddd/aipersimmon-ddd-cqrs/` —— `Command`/`CommandHandler`/`CommandBus`/`CommandInterceptor` 为接口,
  `@Projection`/`@ReadModel` 为注解(同包内的接口/注解划分实证)。
- `aipersimmon-ddd/aipersimmon-ddd-archunit/` —— `commandHandlersShouldNotDependOnOtherCommandHandlers` 靠
  `CommandHandler` 接口识别 handler。
- [[decision-00009-event-type-markers-and-handler-contracts]]、[[decision-00010-command-handler-reuse-and-cross-aggregate-placement]]。

外部:

- jMolecules —— `jmolecules-cqrs-architecture`(`@Command` / `@CommandDispatcher` / `@CommandHandler` / `@QueryModel`)。https://github.com/xmolecules/jmolecules
- Axon Framework Reference —— 消息三分(command 单处理器、有返回值 / event 多处理器)。https://docs.axoniq.io
</content>
</invoke>
