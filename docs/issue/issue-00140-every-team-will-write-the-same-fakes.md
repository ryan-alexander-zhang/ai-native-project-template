---
id: issue-00140-every-team-will-write-the-same-fakes
type: issue
status: resolved
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

2026-07-31 修复。新模块 **`aipersimmon-ddd-test`**（与装容器的 test-support 刻意分开：一个是
纯对象，一个是 Testcontainers 基建；消费方按需各自 test scope 引入），五件套 + 自测 18 条：

- **`RecordingCommandBus`**：保真三条身份规则——`send` 铸 root（环境租户 + 新 correlation）、
  `send(cause)` deriveChild、`sendAs` **原样不铸**（重投去重可测的前提）；`Dispatch(command,
  context, kind)` 记录 + `commandsOf`/`contexts` 读取 + `returning` 打桩。
- **`RecordingIntegrationEvents`**：录完整 `EventEnvelope` 而非裸载荷，信封构造与
  `SpringIntegrationEvents`/outbox writer 同一套规则，经框架自己的 `@EventType` 静态读取器——
  缺注解在测试里就按生产方式炸（`IllegalStateException`）；contract source 覆盖部署默认、
  `publishAs` 原样保 id；固定 Clock 使断言确定。
- **`ImmediateUnitOfWork`**（直通 + 边界计数）、**`InMemoryInbox`**（守住 `(source, messageKey)`
  **对**为身份的契约微妙处；javadoc 言明无事务性——需要回滚语义的是集成测试）。
- **`@WithTenant`** + extension：类/方法级绑定（方法覆盖类）、afterEach **无条件**清理（测试
  中途改绑也不泄漏，有顺序化测试钉住）；值经 `Tenants.fromValue` 信任边界读取。

scaffold 全面换用作为示范（issue 的验收标尺）：inventory-adapter 的手写 `RecordingCommandBus`、
payment-application 与 inventory-application 的两份手写 `RecordingIntegrationEvents` 全部删除，
三个 pom 各加一行 test 依赖，行数净减且断言面变宽（如 `commandsOf(ReserveStock.class)` 取代
强转）。库自身 dogfood：`RecordingCommandBusTest` 就用 `@WithTenant("acme")`。

**在案边界**：(a) scaffold 的 `RecordingHandler`/`RecordingQueries`/`FakePaymentOperations`
是应用自己端口的 fake，不属框架交付物，保留；(b) start 的 `BoundTenant` 保留——它在同一
生命周期上还焊了 Awaitility 同线程轮询（scaffold 私有关注点），javadoc 已标注纯绑定场景
应改用 `@WithTenant`。

验证：`aipersimmon-ddd-test` 模块 18 测全绿并过全部质量门禁；库全 reactor `clean install`
BUILD SUCCESS；scaffold `clean test -pl start -am` 验收套件 BUILD SUCCESS。
