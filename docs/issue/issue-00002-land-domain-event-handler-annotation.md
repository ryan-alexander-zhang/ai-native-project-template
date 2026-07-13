---
id: issue-00002-land-domain-event-handler-annotation
type: issue
role: main
status: resolved
parent: decision-00009-event-type-markers-and-handler-contracts
---

# 落地 @DomainEventHandler 架构语义注解

实施 [[decision-00009-event-type-markers-and-handler-contracts]] 命题二:领域事件订阅者必须带
`@DomainEventHandler` 架构语义注解。集成事件侧不设对应注解(命题三),故本 issue 只涉及领域事件侧。

## 已完成

- **库 · 注解**:在 `aipersimmon-ddd-application` 新增 `DomainEventHandler` 注解
  (`@Documented @Target(TYPE) @Retention(RUNTIME)`,与 `UseCase` 同侪、自包含 javadoc)。
- **库 · 可强制规则(decision-00009 的"必须")**:`AiPersimmonDddRules` 新增
  `domainEventListenersShouldBeAnnotatedWithDomainEventHandler()`——凡 `@EventListener` 且参数为
  `DomainEvent` 的方法,其所在类**必须**标注 `@DomainEventHandler`;并**并入 `all()`**(默认强制)。
  archunit 为此依赖 `aipersimmon-ddd-application`;good fixture `GoodOrderPlacedHandler` 补标注,
  新增 `passesForGood`/`failsForBad` 两条自测(`AiPersimmonDddRulesTest` 15 条全绿)。
- **scaffold / multi-module**:给领域事件订阅者 `OrderFulfilmentStarter` 标注 `@DomainEventHandler`。
- 验证:archunit `mvn install` 全绿(`AiPersimmonDddRulesTest` 15 tests);multi-module `mvn -pl start -am test`
  全绿(6 tests;`ArchitectureTest` 经 `all()` 含新强制规则,`OrderingFlowTest` 两条端到端流程不回归)。

## 未做(后续项)

- **其余脚手架形态**(`modulith`、`microservice`)的领域事件订阅者尚未迁到 application、也未标注
  `@DomainEventHandler`(且已从这两者移除 ArchUnit)。属 [[issue-00001-move-domain-event-listener-to-application]]
  的同类后续;本 issue 按要求**只动 multi-module**。

## 验收标准(GWT)

- **AC-1**:`aipersimmon-ddd-application` 提供 `@DomainEventHandler`(runtime 保留,type 目标)。✅
- **AC-2**:multi-module 的 `OrderFulfilmentStarter` 带 `@DomainEventHandler`。✅
- **AC-3**:multi-module 构建与测试通过,行为不回归。✅
- **AC-4**:存在**可强制**规则要求领域事件订阅者必带 `@DomainEventHandler`,且并入 `all()`;
  以 good/bad fixture 双向自测(注解→通过、缺注解→违规)。✅

## 关联

- 决策:[[decision-00009-event-type-markers-and-handler-contracts]]
- 前序:[[decision-00008-event-subscriber-layer-placement]]、[[issue-00001-move-domain-event-listener-to-application]]
</content>
