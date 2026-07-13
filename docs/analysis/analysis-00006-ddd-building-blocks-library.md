---
id: analysis-00006-ddd-building-blocks-library
type: analysis
role: main
status: active
parent:
---

# DDD 构件库怎么封装：按 Layer 切分的 `aipersimmon-ddd-*` 库

模板框架有两个目标：①脚手架能生成 modulith / multi-module / microservice 三种拓扑
（见 [[analysis-00004-bounded-context-module-structure]]）；②把 DDD 架构语义与分层封装成
**可复用、可版本化**的构件（注解、BC 规则、ArchUnit 校验、聚合根/实体/值对象抽象、
领域事件/集成事件的定义与收发、outbox/inbox、**CQRS 命令总线与读模型**、异常封装）。

本分析回答目标②：**这套构件该怎么组织？该不该"按 Layer 引入"？** 前缀统一为
`aipersimmon-ddd-`。**要求：参考 jMolecules 的实现，但不把它作为依赖**——下面专列一节说明代价与取舍。

配套阅读：[[analysis-00001-domain-event-publishing]]（事件发布机制）、
[[analysis-00002-domain-vs-integration-events]]（两类事件的区分）、
[[analysis-00004-bounded-context-module-structure]]（三种拓扑）、
[[analysis-00005-structure-2-event-flow-and-cqrs]]（CQRS 命令总线 + 读模型的详细设计）、
[[analysis-00007-saga-process-manager]]（跨聚合长流程的 saga/process-manager 构件取舍）。

## 结论先行

> **构件库按 "Layer × 可插拔性" 切分成一组小而独立的 `aipersimmon-ddd-*` 构件 + 一个 BOM，
> 而不是塞进一个 `shared-kernel` 胖 jar。这套库拓扑无关,靠版本号更新。**

- **目标①(脚手架)与目标②(构件库)是正交两条轴**：脚手架是"打包/部署"关注点，
  构件库是"依赖库"关注点。让它们解耦——同一套构件在三种拓扑下是同一批 jar。
- **该按 Layer 引入,准确说是 "按 Layer × 按可插拔性"**：纯语义(domain/application/integration)
  切成少数几个 framework-free、稳定的模块；脏实现(outbox/inbox/messaging/事件桥)切成一堆按技术
  打标签的、可选的 adapter starter。
- **纯净性是硬约束**：不能让 `aipersimmon-ddd-domain` 传递性拖进 Spring/JPA，否则会破坏
  它自己要用 ArchUnit 强制的规则(analysis-00004：domain 必须 framework-free)。
- **不依赖 jMolecules 的代价集中在"集成"而非"词汇"**：注解/接口/基类重写成本低；
  真正贵的是 bytecode 织入与序列化集成——建议明确放弃 bytecode 织入,改用显式注解+基类。

## 一、两条正交轴：别把"拓扑"和"构件库"混在一起

| | 目标①脚手架 | 目标②构件库 |
| --- | --- | --- |
| 关注点 | **打包 / 部署**：几个 jar、几个进程、BC 边界用包/模块/网络强制 | **依赖库**：DDD 语义与分层的可复用抽象 |
| 产物 | 生成一个项目骨架 | 发布到 Maven 的 `aipersimmon-ddd-*` 构件 |
| 变化方式 | 换模板 / 换 starter 组合 | **发新版本号** |
| 与拓扑关系 | 拓扑**就是**它的输出 | **拓扑无关**(唯一例外:消息传输,见§七) |

**关键决策：解耦。** 生成出来的项目**依赖** `aipersimmon-ddd-bom` 的一个固定版本，
而不是把构件源码拷进去。以后"更新目标②",只需 bump BOM 版本,三种脚手架自动受益。
样例里的 `shared-kernel`(见 §八)是为演示方便的内联版,产品化时要变成发布出去的 `aipersimmon-ddd-*`。

## 二、直接回答：为什么"按 Layer × 可插拔性"切分

铁律来自 repo 里 `AggregateRoot` 的注释——**domain 必须 framework-free**。
若把 `AggregateRoot` 和 `OutboxRelay`、Spring listener 打进同一个 jar，纯 domain 模块会
传递性拖进 Spring/JPA,恰好破坏你想用 ArchUnit 禁止的事。所以切分维度必须**对齐每一层
"被允许依赖什么"**,并把"可换的技术实现"单独拆开。

**规律：纯的东西 = 少数几个稳定、framework-free、按层分的模块；
脏的东西 = 一堆按技术打标签、可选的 adapter starter(Spring Boot starter 模式)。**

## 三、`aipersimmon-ddd-*` 模块清单

依赖箭头一律指向内/下,绝不反向。

### 纯净层(framework-free,极少变)

| 模块 | 层 | 依赖 | 内容 |
| --- | --- | --- | --- |
| `aipersimmon-ddd-core` | domain 词汇 | **零依赖** | 注解 `@AggregateRoot`/`@Entity`/`@ValueObject`/`@Repository`/`@Identity`/`@DomainEvent`/`@Service`；分层 stereotype `@DomainLayer`/`@ApplicationLayer`/`@InfrastructureLayer`/`@InterfaceLayer`(+ hexagonal 可选)；marker 接口 `AggregateRoot<ID>`/`Entity<ID>`/`Identifier`/`Association<T,ID>`/`DomainEvent`；基类 `AbstractAggregateRoot`(事件登记/清空)；`DomainException` 基类；**可选** `Transitions<S>` 状态迁移守卫(见§十) |
| `aipersimmon-ddd-application` | application | `-core` | `DomainEvents` 发布 port(见 [[analysis-00001-domain-event-publishing]])；`UseCase` 标记；`ApplicationException` 基类 |
| `aipersimmon-ddd-cqrs` | application(可选) | `-core` | CQRS 契约(见§五)：`Command`/`Query` 标记、`CommandHandler<C>`/`QueryHandler<Q,R>`、`CommandBus`/`QueryBus` port、装饰器 SPI、`ReadModel` 标记、`Projection` 写 port、`UnitOfWork`/`AggregateCollector` 抽象。**framework-free** |
| `aipersimmon-ddd-integration` | api(跨 BC 契约) | `-core`(极薄) | `IntegrationEvent` 基类、`EventEnvelope`(id/type/version/occurredAt/traceId)、版本化约定。拓扑无关的**契约层**(见 [[analysis-00002-domain-vs-integration-events]]) |

### 可插拔实现层(有主见的 adapter starter,可选)

| 模块 | 层 | 依赖 | 内容 |
| --- | --- | --- | --- |
| `aipersimmon-ddd-events-spring` | infrastructure | Spring | `ApplicationEventPublisher`→`DomainEvents` port 桥接;`@TransactionalEventListener` 装配;日志/装饰器 |
| `aipersimmon-ddd-cqrs-spring` | infrastructure(可选) | `-cqrs` + Spring | Spring 实现的 `CommandBus` + 装饰器链(Logging→Validation→Transaction,用 `TransactionTemplate` 接管 UnitOfWork);每请求 `AggregateCollector`(补 MyBatis 无 ChangeTracker,见§五) |
| `aipersimmon-ddd-outbox` | infrastructure(存储无关 core) | `spring-context` + Jackson | 投递契约 `OutboxDispatcher`、存储消息 `OutboxMessage`、默认 dispatcher(logging / in-process)+ dispatch autoconfig;无持久化 |
| `aipersimmon-ddd-outbox-jdbc` / `aipersimmon-ddd-outbox-mybatis-plus`(`-outbox-jpa` 待做) | infrastructure | `-outbox` + JDBC / MyBatis-Plus | outbox 表 writer + relay/poller;两者同表结构可互换,消费者选一个 |
| `aipersimmon-ddd-inbox-jdbc` / `aipersimmon-ddd-inbox-mybatis-plus`(`-inbox-jpa` 待做) | infrastructure | JDBC / MyBatis-Plus | 幂等/inbox,消费方去重;`Inbox` 契约在 `-application` |
| `aipersimmon-ddd-messaging-kafka` / `aipersimmon-ddd-messaging-rabbit` | infrastructure | Kafka / Rabbit | 集成事件传输;**随拓扑选**(见§七) |

### 校验与治理

| 模块 | scope | 内容 |
| --- | --- | --- |
| `aipersimmon-ddd-archunit` | test | 复用规则集(见§六) |
| `aipersimmon-ddd-bom` | import | 统一管住上面所有版本;消费者只 import 这一个 |

## 四、不依赖 jMolecules:参考什么、重写什么、放弃什么

jMolecules(见 `docs/reference/jmolecules/`)本身是 Apache-2.0、dependency-free 的,
技术上完全可以直接依赖。**本项目选择不依赖**,取舍如下——诚实记录:

**为什么不依赖(收益)**
- 词汇归属自己的品牌与命名(`aipersimmon-ddd`),公共 API 不绑定 jMolecules 的发布节奏。
- 只裁剪出本模板的 5 层模型 + outbox/inbox 主张,不背它全部的 onion/CQRS 等注解族。

**参考它的哪些实现(直接照搬设计,自己写代码)**
| jMolecules 提供 | `aipersimmon-ddd` 对应 | 重写成本 |
| --- | --- | --- |
| `jmolecules-ddd` 注解 + 接口(`AggregateRoot`/`Entity`/`ValueObject`/`Identifier`/`Association`) | `aipersimmon-ddd-core` 同名等价物 | **低**(纯声明) |
| `jmolecules-events`(`DomainEvent`/`@Externalized`) | `-core` 的 `DomainEvent` + `-integration` 的外化约定 | **低** |
| `jmolecules-layered/hexagonal-architecture` stereotype | `-core` 的 `@*Layer` 注解 | **低** |
| `jmolecules-cqrs-architecture`(`@Command`/`@CommandHandler`/`@QueryModel`) | `aipersimmon-ddd-cqrs` 的标记 + port + 总线(见§五) | **中**(注解低,总线/装饰器要自己写) |
| `jmolecules-archunit`(`JMoleculesDddRules`) | `aipersimmon-ddd-archunit` 自写规则 | **中**(就是 ArchUnit 谓词,且我们本就要自己的层模型) |

**明确放弃 / 需另做决策(不依赖的真实代价——都在"集成",不在"词汇")**
- `jmolecules-bytebuddy`(把注解在构建期织入 JPA/Spring 样板)——**重写成本高,建议放弃**。
  取而代之:domain 用**显式注解 + 基类**(如 `AbstractAggregateRoot`),持久化在 infrastructure 层
  手写映射(repo 现有 `*Po`/`*Mapper` 已是此路子)。这是不依赖 jMolecules 最大的一笔代价。
- `jmolecules-spring` / `jmolecules-jackson`(Identifier/VO 的边界序列化)——由
  `aipersimmon-ddd-events-spring` 或一个 `aipersimmon-ddd-jackson` 按需补齐,不追求全等价。

> 一句话:**不依赖 jMolecules 不影响"表达 DDD 语义",只是把"自动织入"这项便利换成"显式手写"。**
> 对一个要强调边界清晰、可审计的模板来说,这个交换是可接受的。

## 五、CQRS:命令侧与查询侧对称,且整体可选

CQRS 是这个模板的重头戏(详见 [[analysis-00005-structure-2-event-flow-and-cqrs]] §5)——
它横跨 application 与 infrastructure,契约放 `aipersimmon-ddd-cqrs`(纯),实现放
`aipersimmon-ddd-cqrs-spring`(starter),严守本文"纯/脏分离"。

**先讲优先级:CQRS 整体可选(YAGNI)。** `domain-driven-hexagon` 明说命令总线 "optional",
`ddd-by-examples/library` 无总线也成立;本模板为**展示可集中治理的完整命令管道**而提供它,
但脚手架应允许"只用 `-application`、不引 `-cqrs`"的最小形态。

### 命令侧(写)—— `aipersimmon-ddd-cqrs` 契约 + `-cqrs-spring` 实现

- **契约(纯)**：`Command` 标记(一等、任务型命令对象,如 `PlaceOrderCommand`)、
  `CommandHandler<C>`(薄 handler,只编排聚合)、`CommandBus` port、装饰器 SPI、
  `UnitOfWork` / `AggregateCollector` 抽象。
- **实现(starter)**：Spring 版 `CommandBus` + **装饰器链 Logging→Validation→Transaction**;
  Transaction 装饰器用 `TransactionTemplate` 接管 UnitOfWork,使 handler 保持纯净、横切统一治理。
- **一个务实点(analysis-00005 §5)**：MyBatis 无 EF 式 ChangeTracker,事件集中 drain 需补一个
  **每请求 `AggregateCollector`**——归 `-cqrs-spring`,不污染纯契约。

### 查询侧(读)—— 真正绕过写模型

- **`ReadModel` 标记 + 查询 port**(如 `OrderQueries`):查询直打投影视图,**不经聚合、不经写仓储**
  (repo 现状:`OrderQueries.byId` → `OrderSnapshot`,绕过 `Orders.byId`)。
- **`Projection` 写 port**(如 `OrderProjection`):由领域事件**同事务**在进程内更新读模型
  (repo 现状:`OrderProjection.placed/statusChanged`)——与事件链路(§一/[[analysis-00001-domain-event-publishing]])对齐。
- `QueryBus`/`QueryHandler<Q,R>` 可选;读侧简单时直接注入 port 即可,不必上总线。

> 归属小结:命令/查询**契约**在 `-cqrs`(纯、可选);命令**总线+装饰器+AggregateCollector**
> 在 `-cqrs-spring`(脏、可选);读模型**实现**(直查 DB 的视图)在各 BC 的 infrastructure。

## 六、`aipersimmon-ddd-archunit`:规则一套,强制点随拓扑不同

规则打进 test-scope 库,三种拓扑复用同一份;差别只在**边界靠什么强制**(承接 analysis-00004):

| 拓扑 | BC 边界 | 层规则强制点 |
| --- | --- | --- |
| structure-1 modulith | 包 | `ApplicationModules.verify()` + `aipersimmon-ddd-archunit` |
| structure-2 multi-module | Maven 模块(编译期) | `aipersimmon-ddd-archunit` 管**模块内**层规则 |
| structure-3 microservice | 网络 | 每服务复用同一份"服务内"层规则 |

规则集示例:domain 不得依赖 application/infrastructure/adapter/任何 framework;
跨聚合只能经 `Association`/`Identifier` 引用聚合根,不得直接对象引用;
`IntegrationEvent` 只能出现在 `*-api`/`-integration`;`DomainEvent` 不得泄漏到 adapter。

## 七、构件库拓扑无关,唯一例外是消息传输

- modulith:集成事件可走进程内(Spring Modulith event externalization)。
- microservice:必须过 Kafka/Rabbit。
- **但 `aipersimmon-ddd-integration` 契约在三种拓扑下字节一致**——变的只是选哪个
  `aipersimmon-ddd-messaging-*` starter。这就是"契约与传输分离"。

## 八、把 `shared-kernel` 拆掉(承接概念澄清)

样例里的 `shared-kernel` 混了两种东西:**技术 seedwork**(`AggregateRoot`/`DomainEvent`/
`DomainEvents`)和**真·共享领域概念**(`Money`)。产品化时:

- 技术 seedwork → 上移为发布出去的 `aipersimmon-ddd-core` / `-application`,**不再叫 shared-kernel**,谁都能依赖。
- `shared-kernel` 之名留给"两个具体 BC 协议共享的领域概念"(如 `Money`),按 BC-pair 可选引入,
  并用 ArchUnit 限制其扩散——避免它膨胀成把所有上下文重新耦合的"上帝模块"。

## 九、落地建议

1. 建 `aipersimmon-ddd-bom` + §三的模块骨架,先只做 `-core`/`-application`/`-integration`
   三个纯净模块 + `-archunit`;把 repo 现有 `shared-kernel` 的 `AggregateRoot`/`DomainEvent`/
   `DomainEvents` 迁进去验证纯净性(零 framework 依赖)。
2. outbox/inbox 先做 `-outbox-jpa` + `-inbox-jpa`(repo 已有 `OutboxRelay`/`ProcessedResult*` 可抽取)。
3. 脚手架(目标①)生成的项目**依赖 BOM 固定版本**,并按拓扑选 starter 组合;不拷源码。
4. bytecode 织入明确不做;domain 用显式注解 + 基类 + infrastructure 手写映射。

## 十、附:`-core` 的 `Transitions<S>` 状态迁移守卫(含 demo)

`aipersimmon-ddd-core` 里一个**极小、纯、framework-free 的可选**工具,用于聚合/实体的
生命周期迁移守卫。**它不是基类、不是状态机引擎——聚合"用"它,而不是"继承"它;
表面仍暴露 `confirm()`/`cancel()` 这类意图揭示方法(通用语言在外),迁移表集中一处。**
零依赖,可直接放进纯净的 domain 层。

> 与 saga 状态机区分:这是**聚合级**(单聚合、单本地事务、无持久化流程状态);跨聚合的**流程级**
> 状态机是 [[analysis-00007-saga-process-manager]] 的 `aipersimmon-ddd-saga`,是另一套抽象,勿复用。

### 工具本体(包名示意 `com.aipersimmon.ddd.core.state`)

```java
package com.aipersimmon.ddd.core.state;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 极小、零依赖的状态迁移守卫。把合法迁移声明一次(通常作为聚合的
 * {@code private static final} 字段),然后在意图揭示方法内部调用 {@link #check}。
 * 不是基类、不是引擎:聚合 USE 它,不 extend 它。framework-free。
 */
public final class Transitions<S> {

    private final Map<S, Set<S>> allowed = new HashMap<>();

    private Transitions() {}

    public static <S> Transitions<S> of() {
        return new Transitions<>();
    }

    /** 声明一条合法迁移 {@code from -> to};返回 this 以便链式声明。 */
    public Transitions<S> allow(S from, S to) {
        allowed.computeIfAbsent(from, k -> new HashSet<>()).add(to);
        return this;
    }

    /** 是否为已声明的合法迁移。 */
    public boolean permits(S from, S to) {
        return allowed.getOrDefault(from, Set.of()).contains(to);
    }

    /** 守卫:非法迁移则抛 {@link IllegalStateTransitionException}。 */
    public void check(S from, S to) {
        if (!permits(from, to)) {
            throw new IllegalStateTransitionException(from, to);
        }
    }
}
```

```java
package com.aipersimmon.ddd.core.state;

/** 聚合/实体被要求执行未声明的状态迁移时抛出。 */
public final class IllegalStateTransitionException extends RuntimeException {
    public IllegalStateTransitionException(Object from, Object to) {
        super("illegal state transition: " + from + " -> " + to);
    }
}
```

### demo:`Order` 聚合内部用它(对外仍是 `confirm()`/`cancel()`)

```java
import com.aipersimmon.ddd.core.state.Transitions;
import static com.acme.samples.s2.ordering.domain.order.OrderStatus.*;

public class Order extends AbstractAggregateRoot {

    // 迁移表集中一处;S 由 allow 的参数推断为 OrderStatus
    private static final Transitions<OrderStatus> RULES = Transitions.<OrderStatus>of()
            .allow(PENDING, CONFIRMED)
            .allow(PENDING, CANCELLED);

    private OrderStatus status = PENDING;

    /** 通用语言在外:业务说“确认订单”,不是“触发某事件”。 */
    public void confirm() {
        RULES.check(status, CONFIRMED);        // 守卫集中,非法迁移即抛
        this.status = CONFIRMED;
        registerEvent(new OrderConfirmedEvent(id()));
    }

    public void cancel() {
        RULES.check(status, CANCELLED);
        this.status = CANCELLED;
        registerEvent(new OrderCancelledEvent(id()));
    }
}
```

**要点**:①状态少时可不用它(直接 `if` 守卫即可),它是**可选**的;②复杂生命周期(状态多、迁移多)时,
集中迁移表能消除散落 `if` 的重复与遗漏;③始终**不**把 `transition(event)` 这种机械 API 暴露到聚合外——
迁移表是内部实现,`confirm()`/`cancel()` 才是通用语言。

## Sources

Internal(distilled,在 `docs/reference/`):
`jmolecules/`(注解/接口/architecture/integrations 清单与"该借鉴什么")、
`modular-monolith-with-ddd/`(Outbox/Inbox、module=BC)、
`spring-modulith-with-ddd/`(event externalization `mapping()` + `@Externalized`)。

下游:本文的 `-core` `DomainException` / `-application` `ApplicationException` 基类,其完整异常体系
(`ErrorCode` 贯通、`BusinessRule`、语义子类、消息可靠性)见 [[analysis-00010-exception-model]] 与 [[design-00003-exception-model]]。

External:

- xmolecules/jMolecules — https://github.com/xmolecules/jmolecules · https://jmolecules.org （参考实现,不作依赖）
- jMolecules integrations(bytebuddy / spring / jackson / archunit) — https://github.com/xmolecules/jmolecules-integrations
- Spring Modulith — Fundamentals — https://docs.spring.io/spring-modulith/reference/fundamentals.html
- Microsoft, *.NET Microservices: Architecture for Containerized .NET Applications*(DomainEvent vs IntegrationEvent + IntegrationEventLog=Outbox) — https://learn.microsoft.com/dotnet/architecture/microservices/
- Spring Boot — Creating Your Own Starter / Dependency Management(BOM) — https://docs.spring.io/spring-boot/reference/features/developing-auto-configuration.html
- Alibaba COLA — https://github.com/alibaba/COLA
- Greg Young / Udi Dahan — task-based commands & CQRS(命令总线可选、读模型绕过聚合的依据,详见 [[analysis-00005-structure-2-event-flow-and-cqrs]] Sources）
