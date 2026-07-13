---
id: issue-00002-land-domain-event-handler-annotation
type: issue
role: main
status: open
parent: decision-00009-event-type-markers-and-handler-contracts
---

# 落地 @DomainEventHandler 架构语义注解

实施 [[decision-00009-event-type-markers-and-handler-contracts]] 命题二:领域事件订阅者必须带
`@DomainEventHandler` 架构语义注解。集成事件侧不设对应注解(命题三),故本 issue 只涉及领域事件侧。

## 已完成(本次)

- **库**:在 `aipersimmon-ddd-application` 新增 `com.aipersimmon.ddd.application.DomainEventHandler`
  注解(`@Documented @Target(TYPE) @Retention(RUNTIME)`,与 `UseCase` 同侪、同风格,自包含 javadoc)。
- **scaffold / multi-module**:给领域事件订阅者 `OrderFulfilmentStarter` 标注 `@DomainEventHandler`。
- 验证:`mvn -pl start -am test` 全绿(8 tests;含 `ArchitectureTest` 4 条规则与 `OrderingFlowTest` 两条端到端流程)。

## 未做(后续项)

- **其余脚手架形态**(`modulith`、`microservice`)的领域事件订阅者尚未标注(且其订阅仍在 adapter 层,
  见 [[issue-00001-move-domain-event-listener-to-application]] 的同类后续)。本 issue 按要求**只动 multi-module**。
- **强制"必须"**:当前 `AiPersimmonDddRules` 的
  `domainEventListenersShouldResideInApplicationOrDomain()` 靠 `@EventListener` + 参数类型识别 listener,
  **不要求**其带 `@DomainEventHandler`。若要把 decision-00009 的"必须"落成可强制约束,需新增一条规则
  (语义:凡 `@EventListener` 且参数为 `DomainEvent` 的方法,其所在类须标注 `@DomainEventHandler`),
  并考虑让 `domainEventListenersShouldResideInApplicationOrDomain` 改为按注解定位。作为独立 issue 处理。

## 验收标准(GWT)

- **AC-1**:`aipersimmon-ddd-application` 提供 `@DomainEventHandler`(runtime 保留,type 目标)。✅
- **AC-2**:multi-module 的 `OrderFulfilmentStarter` 带 `@DomainEventHandler`。✅
- **AC-3**:multi-module 构建与测试通过,行为不回归。✅

## 关联

- 决策:[[decision-00009-event-type-markers-and-handler-contracts]]
- 前序:[[decision-00008-event-subscriber-layer-placement]]、[[issue-00001-move-domain-event-listener-to-application]]
</content>
