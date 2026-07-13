---
id: issue-00004-enforce-no-command-handler-to-command-handler-dependency
type: issue
role: main
status: resolved
parent: decision-00010-command-handler-reuse-and-cross-aggregate-placement
---

# 机器固化:CommandHandler 不得依赖 CommandHandler

落地 [[decision-00010-command-handler-reuse-and-cross-aggregate-placement]] 规则一。该决策已确立
"`CommandHandler` 之间不得互相依赖"(它是命令总线入口点,互调会绕过 / 双重应用横切、污染 Command 契约、
破坏 UnitOfWork 边界),但目前**仅靠人工 review**,无机制强制。本 issue 追踪加一条 ArchUnit 规则把它固化。

## 背景

- 规则出处:[[decision-00010-command-handler-reuse-and-cross-aggregate-placement]] 规则一。
- 现状:`aipersimmon-ddd-archunit` 的 `AiPersimmonDddRules` 已用同一范式固化过 handler 契约
  (见 [[decision-00009-event-type-markers-and-handler-contracts]] / [[issue-00002-land-domain-event-handler-annotation]]
  的 `domainEventListenersShouldBeAnnotatedWithDomainEventHandler()`);本条与之同批维护。

## 已完成

- **库 · 依赖**:`aipersimmon-ddd-archunit` 增加对 `aipersimmon-ddd-cqrs` 的编译依赖(cqrs 仅依赖 core,
  轻量),使规则可直接引用 `CommandHandler` 契约类型——与既有规则引用 `DomainEvent`/`IntegrationEvent`/
  `@DomainEventHandler` 的做法一致(cqrs 是本库一等模块,不同于按名字符串匹配的外部框架 Spring)。
- **库 · 规则**:`AiPersimmonDddRules` 新增 `commandHandlersShouldNotDependOnOtherCommandHandlers()`——
  凡 `implements CommandHandler` 的类,其直接依赖里不得出现"另一个 `CommandHandler` 实现"。以自定义
  `ArchCondition`(`notDependOnAnotherCommandHandler`)遍历 `getDirectDependenciesFromSelf()`,对目标做
  `isAssignableTo(CommandHandler)` 判定,并**排除两类假阳性**:`CommandHandler` 接口自身
  (`isEquivalentTo`)与自依赖(同名 origin)。用 `classes().should(...)` 承载(`violated` 即违规),
  `allowEmptyShould(true)` 保证无 handler 的项目空匹配通过。
- **并入 `all()`**:作为默认强制规则,`multi-module/start/…/ArchitectureTest` 经 `all()` 自动获得。
- **双向自测**:good fixture `GoodConfirmOrderHandler`(依赖 domain `GoodOrder` + 非-handler 协作者
  `GoodPlaceOrderService`)通过;bad fixture `BadCancelOrderHandler`(构造注入另一个 handler
  `BadConfirmOrderHandler`)违规。新增 `passesForGood`/`failsForBad` 两条自测。
- **验证**:archunit `mvn install` 全绿(`AiPersimmonDddRulesTest` 17 tests,+2);multi-module
  `mvn -pl start -am test` 全绿(11 tests;`ArchitectureTest` 经 `all()` 含新规则,两条端到端流程不回归)。
  脚手架 Place/Confirm/Cancel 三个 handler 互不依赖,自然满足新规则。

## 验收标准(GWT)

- **AC-1**:`AiPersimmonDddRules` 提供 `commandHandlersShouldNotDependOnOtherCommandHandlers()` 并入 `all()`。✅
- **AC-2**:以 good/bad fixture 双向自测(handler→handler 依赖→违规;handler→非-handler 协作者→通过)。✅
- **AC-3**:archunit 构建与既有 multi-module 测试全绿,无回归。✅
- **AC-4**:规则排除"接口自身"与"自依赖"两类假阳性,对无 CQRS 契约的项目空匹配通过。✅

## 关联

- 决策:[[decision-00010-command-handler-reuse-and-cross-aggregate-placement]]
- 同范式前序:[[decision-00009-event-type-markers-and-handler-contracts]]、[[issue-00002-land-domain-event-handler-annotation]]
