---
id: analysis-00001-domain-event-publishing
type: analysis
role: main
status: active
parent:
---

# 领域事件的发布与消费：跨 reference 对比 + 可插拔发布策略实现

对 `docs/reference/` 下 8 个 DDD 参考项目的领域事件（Domain Event）**发布/消费机制**做横向对比，
并把 `ddd-by-examples/library` 的**可插拔发布策略**（store-and-forward / immediate / metered）连同
核验过的源码落盘，作为本模板（`lang/java/ddd`）的事件发布蓝本。

源码核验：`ddd-by-examples/library` @ `98d7004b5aef366ba5d661ae0cb544af1cfd33f1`（2022-05-25），
2026-07-08 从 upstream 拉取核对。其余机制描述取自 `docs/reference/<slug>/` 笔记。

## 结论先行

- **进程内、跨聚合的领域事件默认应同步、同事务**——它表达"刚发生的业务事实"，消费方常需在同一事务边界内响应。
- Java/Spring 的标准做法是 `ApplicationEventPublisher.publishEvent()` + `@EventListener`，**默认同步、同线程、同事务**。
- **一旦需要异步 / 持久 / 不丢，语义就滑向集成事件**——用 Outbox（store-and-forward）承接。
- library 的巧思：**同一个 `DomainEvents` 接口 + 装饰器**，把"同步立即发"和"异步持久发"做成可热插拔的实现，业务代码零改动。
- 区分领域事件 vs 集成事件的判定轴：**能否一次编译抓到所有下游**。能 → 概念区分即可；不能（跨网络/独立部署）→ 必须两套类型 + 版本化契约。详见 [[analysis-00002-domain-vs-integration-events]]。

## 一、跨 reference 的 publish / consume 对比

| 项目 | Publish（发出） | Consume（消费） | 事务 / 时机 |
| --- | --- | --- | --- |
| ddd-by-examples-factory | model 声明端口 `DemandEvents`；`DemandEventsPropagation`（app 层）扇出 | 直接调用 projection / 其他上下文方法 | 进程内**同步**，无 broker |
| ddd-by-examples-library | `StoreAndForwardDomainEventPublisher` + `EventsStorage`（persist-then-publish） | Spring `@EventListener`（如 `CreateAvailableBookOnInstanceAddedEventHandler`、`PatronEventsHandler`） | Outbox 式；可换 `JustForward`（立即）/ `Metered` |
| modular-monolith-with-ddd | 聚合 `AddDomainEvent()`；UnitOfWork 提交时分发 | 进程内 handler；跨模块转 IntegrationEvent 走 Outbox→Inbox | 领域事件**同事务**；集成事件异步 at-least-once |
| spring-modulith-with-ddd | `AbstractAggregateRoot.registerEvent(...)`，save 时分发 | `@ApplicationModuleListener`（事务绑定、异步） | event publication registry（JPA，outbox 式）持久 |
| axon-framework | `AggregateLifecycle.apply(event)` → `EventBus` / `EventStore` | `@EventHandler` 投影器 + `@EventSourcingHandler` 重建 + `@SagaEventHandler` | Tracking（异步、可 replay）vs Subscribing（发布事务内） |
| clean-architecture (Ardalis) | `RegisterDomainEvent(...)`；EF Core `SaveChangesInterceptor` | `IDomainEventDispatcher` + 同层 handler（`Core/.../Handlers`） | **after-commit**（保存成功才 fire） |
| domain-driven-hexagon | 领域事件 + command/query bus | in-process handler，**事务内**解耦聚合 | 进程内、事务内 |
| jmolecules | 只给注解/接口：`@DomainEventPublisher`、`DomainEvent` | `@DomainEventHandler` 注解 | 无运行时——由适配框架（如 Spring Modulith）执行 |

### 按"发出后靠什么送达"归类

- **A. 纯进程内同步**（最简单，不保证不丢）：factory、domain-driven-hexagon。
- **B. Outbox / persist-then-publish**（保证送达）：ddd-by-examples-library、modular-monolith-with-ddd、spring-modulith-with-ddd。
- **C. Event Sourcing**（事件即真相之源）：axon-framework。
- **D. 只有约定，无运行时**：jmolecules（交给底层框架）；clean-architecture 为 after-commit 进程内，无集成事件/outbox。

## 二、领域事件的同步语义（Spring）

进程内、跨聚合的领域事件默认同步、同事务。Spring `ApplicationEventPublisher.publishEvent()` 默认即为
同步、同线程、在调用方事务内执行，`@EventListener` 内联跑。

三档同步语义按需选：

| 想要 | 用什么 |
| --- | --- |
| 同步、同事务（默认） | `@EventListener` |
| 同步、但仅在**提交后**执行（避免脏读 / 回滚后已发） | `@TransactionalEventListener(phase = AFTER_COMMIT)` |
| 异步（脱离原线程 / 事务）——已滑向集成事件 | 监听方法加 `@Async` |

消费端示例（`@EventListener` 无 `@Async`，因此同步）：

```java
@AllArgsConstructor
public class CreateAvailableBookOnInstanceAddedEventHandler {
    private final BookRepository bookRepository;

    @EventListener
    void handle(BookInstanceAddedToCatalogue event) {
        bookRepository.save(new AvailableBook(new BookId(event.getBookId()),
                event.getType(), ourLibraryBranch(), Version.zero()));
    }
}
```

## 三、可插拔发布策略（ddd-by-examples/library）—— 装饰器 + Outbox

### 1. 接口（契约）

应用层只依赖这个接口，不知道背后是哪档策略——可插拔的根基。

```java
public interface DomainEvents {
    void publish(DomainEvent event);

    default void publish(List<DomainEvent> events) {   // vavr List
        events.forEach(this::publish);
    }
}
```

### 2. 三个实现，各司其职

`JustForward` —— 立即同步发（直接转 Spring）：

```java
@AllArgsConstructor
public class JustForwardDomainEventPublisher implements DomainEvents {
    private final ApplicationEventPublisher applicationEventPublisher;

    @Override
    public void publish(DomainEvent event) {
        applicationEventPublisher.publishEvent(event);   // 同步、同线程、同事务
    }
}
```

`Metered` —— 指标装饰器（包一个 delegate，发完计数，自己不决定怎么发）：

```java
@AllArgsConstructor
public class MeteredDomainEventPublisher implements DomainEvents {
    private static final String DOMAIN_EVENTS = "domain_events";
    private static final String TAG_NAME = "name";

    private final DomainEvents delegate;              // 包着另一个 DomainEvents
    private final MeterRegistry metricsRegistry;

    @Override
    public void publish(DomainEvent event) {
        delegate.publish(event);
        metricsRegistry.counter(DOMAIN_EVENTS, TAG_NAME,
                event.getClass().getSimpleName()).increment();
    }
}
```

`StoreAndForward` —— 持久化 + 轮询转发（即 Outbox）：

```java
@AllArgsConstructor
public class StoreAndForwardDomainEventPublisher implements DomainEvents {
    private final DomainEvents eventsPublisher;   // 真正往外发的那层
    private final EventsStorage eventsStorage;    // 落库

    @Override
    public void publish(DomainEvent event) {
        eventsStorage.save(event);                // publish 时只【存】，不发
    }

    @Scheduled(fixedRate = 3000L)                 // 每 3 秒
    @Transactional
    public void publishAllPeriodically() {
        List<DomainEvent> domainEvents = eventsStorage.toPublish();
        domainEvents.forEach(eventsPublisher::publish);   // 转发给内层
        eventsStorage.published(domainEvents);            // 标记已发
    }
}
```

存储端口（实现可换 InMemory / JDBC）：

```java
public interface EventsStorage {
    void save(DomainEvent event);
    List<DomainEvent> toPublish();
    void published(List<DomainEvent> events);
}
```

### 3. 组装 —— 一个 `@Bean` 叠成装饰器链

```java
@Bean @Primary
DomainEvents domainEventsWithStorage(ApplicationEventPublisher publisher,
                                     MeterRegistry meterRegistry) {
    return new StoreAndForwardDomainEventPublisher(      // 外：先落库、后台轮询转发
             new MeteredDomainEventPublisher(            // 中：计数
               new JustForwardDomainEventPublisher(publisher),  // 内：真正发到 Spring
               meterRegistry),
             new InMemoryEventsStorage());
}
```

调用链：`publish()` → StoreAndForward **只存库** → 每 3s poller 取出 → Metered **计数**
→ JustForward **真发** → `@EventListener` 消费。

> 上面这段组装来自 library 的 `integration-test` 配置（`DomainEventsTestConfig`），
> 用了 `InMemoryEventsStorage`——demo/测试用途；生产务必换 JDBC 版存储。

## 四、落到本模板的实现建议（Java + Spring）

1. 定义 `DomainEvents` 接口，应用层只依赖它。
2. `JustForward` 用 `ApplicationEventPublisher`（同步、同事务）——大多数进程内领域事件到此为止。
3. 需要可靠跨边界时，叠 `StoreAndForward` + **JDBC 版 `EventsStorage`**（勿用 InMemory），poller 用 `@Scheduled`。
4. 横切关注点（metrics / log / tracing）一律做成装饰器，勿写进业务或分支。
5. 生产可把 `@Scheduled` 轮询升级为更强机制（Debezium CDC，或 Spring Modulith 的 event publication registry），语义一致、可靠性更高。
6. **换策略 = 换 `@Bean` 组装**，业务代码零改动——这是该设计的核心价值。

## 相关参考

- `docs/reference/ddd-by-examples-library/` —— 本分析的主源。
- `docs/reference/spring-modulith-with-ddd/` —— 生产级 outbox 式发布注册表 + 事件外化 `mapping()`。
- `docs/reference/modular-monolith-with-ddd/` —— 两级事件（domain / integration）+ Outbox/Inbox。
- `docs/reference/axon-framework/` —— Event Sourcing 路线（仅在确需 ES 时采用）。
