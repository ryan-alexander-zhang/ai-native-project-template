---
id: issue-00140-every-team-will-write-the-same-fakes
type: issue
role: main
status: open
---

# 框架定义了六个端口，每个消费团队都得自己手搓同一批 fake

2026-07-30 全面评审（P1）。

## 问题

`aipersimmon-ddd-test-support` 全部 8 个类都是容器/连接细节（`SharedContainers`、
`*ServiceConnection`、`DockerAvailable`）；框架的核心端口——`CommandBus`/`QueryBus`/
`DomainEvents`/`IntegrationEvents`/`Inbox`/`UnitOfWork`——没有任何单测级 in-memory 实现。

scaffold 自己就是证据，同构造物在三个 BC 里重复出现：手写 `RecordingCommandBus`
（`inventory-adapter/.../OrderReadyForFulfilmentVersionsTest.java:122`）、
`RecordingIntegrationEvents`（`payment-application/.../AuthorizePaymentIdempotencyTest.java:147`）、
多个 `RecordingHandler`/`RecordingQueries`/`FakePaymentOperations`。

## 根因（第一性）

- 期望：测一个 handler 不需要 Docker，也不需要先正确地重新发明 `sendAs` 语义。
- 分歧机制：fake 写错的方式很多且隐蔽——fake `CommandBus` 不铸 messageId、fake
  `IntegrationEvents` 不校验 `@EventType`——每个团队各写一份，语义与真实实现渐行渐远。
- 真根因：框架把"端口"作为卖点，却没有把"端口的测试替身"当作交付物的一部分。
  另有 `TenantContext`/MDC 是 ThreadLocal，测试间泄漏需要每个团队自己写 `@AfterEach` 清理。

## 复现

不适用（缺失能力类）。验收标尺：scaffold 删掉全部手写 Recording/Fake 类改用官方件后，
测试仍绿且行数净减。

## 改法

新增 `aipersimmon-ddd-test` 模块（或 test-support 的非容器包）：

- `RecordingCommandBus`（可断言 dispatched commands + contexts，`sendAs` 语义正确）
- `RecordingIntegrationEvents`（断言信封完整性，复用真实的 `@EventType` 读取逻辑）
- `ImmediateUnitOfWork`、`InMemoryInbox`
- JUnit 5 extension `@WithTenant("t1")`（负责绑定与 `@AfterEach` 清理）

scaffold 全面换用，作为用法示范。

## 验证结果

未修复。
