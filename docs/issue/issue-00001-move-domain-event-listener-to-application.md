---
id: issue-00001-move-domain-event-listener-to-application
type: issue
role: main
status: resolved
parent: decision-00008-event-subscriber-layer-placement
---

# 把领域事件订阅移出 adapter,让 ordering-adapter 不再依赖 domain

实施 [[decision-00008-event-subscriber-layer-placement]]:`ordering-adapter/messaging/OrderFulfilment`
当前用 `@EventListener` 订阅内部领域事件 `OrderPlacedEvent` 来启动 saga,这是 `ordering-adapter`
模块里**唯一**依赖 `ordering-domain` 的原因。按决策,领域事件订阅应归 application 层。

范围:`aipersimmon-ddd-scaffold/multi-module/ordering`。其余脚手架形态(`modulith`、`microservice`)
的对应类若同样订阅领域事件,作为后续同类项处理(本 issue 先做 multi-module 作示范)。

## 现状

- `ordering-adapter/messaging/OrderFulfilment`:三个 `@EventListener`——
  `onOrderPlaced(OrderPlacedEvent)`(领域事件,内部)、`onStockReserved(StockReserved)`、
  `onStockReservationFailed(StockReservationFailed)`(集成事件,来自 inventory-api)。
- `ordering-adapter/pom.xml` 依赖 `ordering-domain`。
- `OrderFulfilmentProcessManager`(application)持编排策略,传输无关。

## 目标

- 领域事件 `OrderPlacedEvent` 的订阅**移到 application 层**。
- messaging adapter 只保留**集成事件**订阅(`StockReserved`/`StockReservationFailed`),并按决策
  倾向翻译成 command(见"附:集成事件侧")。
- `ordering-adapter` **去掉对 `ordering-domain` 的依赖**(pom + import)。

## 两个方案

### 方案 1(本 issue 先做)—— application 层 `@EventListener` 订阅领域事件 ✅ 首选

在 `ordering-application/.../fulfilment` 新增一个 application 层订阅者,例如
`OrderFulfilmentStarter`(或直接在 `OrderFulfilmentProcessManager` 上加 `@EventListener`):

```java
@Component
class OrderFulfilmentStarter {
    private final OrderFulfilmentProcessManager process;
    OrderFulfilmentStarter(OrderFulfilmentProcessManager process) { this.process = process; }

    @EventListener
    void onOrderPlaced(OrderPlacedEvent event) {
        process.onOrderPlaced(event.orderId().value());
    }
}
```

- 领域事件仍进程内、同事务、同步发布(`PlaceOrderHandler.publishAll`),application 订阅者在同一事务内启动 saga,时序保证不变。
- **额外价值:这就是一个"application 层订阅领域事件"的范例**——脚手架当前缺这个示范,补上后与集成事件订阅(adapter)形成清晰对照。
- 从 `ordering-adapter/messaging/OrderFulfilment` 删除 `onOrderPlaced` 与对 `OrderPlacedEvent` 的 import。

### 方案 2(备选,记录备查)—— 在用例内直接启动 saga

在 `PlaceOrderHandler.handle(...)` 保存 order 后直接 `sagas.save(new OrderFulfilmentSaga(orderId))`,
不经领域事件。更简单、最少一跳,但**不产生领域事件订阅的示范**,且把"启动 saga"与"下单用例"耦合在一处。

> 取舍:脚手架以**教学示范**为要,故先做方案 1(展示 application 层领域事件订阅);方案 2 作为
> "追求最简、无需解耦启动"时的备选,在此记录,暂不实施。

## 附:集成事件侧(随本 issue 一并对齐)

`onStockReserved`/`onStockReservationFailed` 留在 messaging adapter。按 [[decision-00008-event-subscriber-layer-placement]]
命题 C,倾向让 adapter 把集成事件**翻译成 command** 经 `CommandBus` 入 application,而非直接调用 process manager。
此项若改动面较大,可拆为后续 issue;本 issue 至少保证 adapter 不因集成事件而依赖 domain(集成事件类型在
`inventory-api`,非 domain,故不阻塞去依赖目标)。

## 验收标准(GWT)

- **AC-1**:构建后,`ordering-adapter` 模块无任何 `import com.example.ordering.domain.*`;`ordering-adapter/pom.xml`
  不再声明对 `ordering-domain` 的依赖。
- **AC-2**:存在一个 application 层类订阅 `OrderPlacedEvent` 并启动 saga(方案 1)。
- **AC-3**:下单 → saga 启动 → 库存响应 → confirm/cancel 的既有测试全绿;saga 在库存响应到达前已存在(时序不回归)。
- **AC-4**:(可选)新增/更新 ArchUnit 规则:`ordering-adapter` 不得依赖 `…ordering.domain`。

## 结果(方案 1 已实施并验证)

范围:`aipersimmon-ddd-scaffold/multi-module`。

- 新增 `ordering-application/.../fulfilment/OrderFulfilmentStarter`:application 层 `@EventListener`
  订阅 `OrderPlacedEvent` 启动 saga(作为"application 层领域事件订阅"的示范)。
- `ordering-adapter/messaging/OrderFulfilment`:删除 `onOrderPlaced` 与 `OrderPlacedEvent` import,
  只保留集成事件订阅;更新 javadoc。
- `ordering-adapter/pom.xml`:移除 `ordering-domain` 依赖(并清理未使用的 `aipersimmon-ddd-saga`;
  `aipersimmon-ddd-core` 保留,因 `@InterfaceLayer` / `DomainException` 仍在用);更新模块注释。
- **AC-4**:`start/.../ArchitectureTest` 新增规则 `orderingAdapterDoesNotDependOnDomain`,固化本决策。

验证:`mvn -pl start -am test` 全绿(5 tests)。其中端到端 `OrderingFlowTest` 两条(下单→预留→
`CONFIRMED`;预留失败→补偿→`CANCELLED`)通过,证明 saga 由 application 订阅者正常启动、时序不回归;
新增 ArchUnit 规则通过,证明 `ordering-adapter` 已无 `ordering.domain` 依赖。

**订正**:早先此处曾把"`StockReserved`/`StockReservationFailed` 未翻译成 command"列为缺口——**这是误判**。
按 [[decision-00008-event-subscriber-layer-placement]] 命题 C2,推进 saga 的集成事件**不应**包成 command,
而应递交给 process manager(saga 反应后自行发 command)。当前 `OrderFulfilment` 直接调 `process.onStockReserved(...)`
是**正确**的。命题 C1(驱动聚合的集成事件 → command)则由 `inventory-adapter/OrderPlacedListener` 体现。二者均已在 sample 中呈现。

**未做(后续项)**:

- 其余脚手架形态(`modulith`、`microservice`)的同类改动未做(领域事件订阅仍在 adapter),作为后续同类项。

## 关联

- 决策:[[decision-00008-event-subscriber-layer-placement]]
- 背景分析:[[analysis-00009-saga-implementation-deep-dive]]、[[analysis-00002-domain-vs-integration-events]]
</content>
