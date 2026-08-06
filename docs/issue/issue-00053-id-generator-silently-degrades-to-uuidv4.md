---
id: issue-00053-id-generator-silently-degrades-to-uuidv4
type: issue
status: resolved
parent: report-00001-ddd-framework-review
---

# 缺少 `aipersimmon-ddd-id` 时五处铸造点静默退回 UUIDv4：立项要消除的索引写放大被悄悄放回，无任何启动信号

## 问题（现状，file:line 为证）

- **等级：Medium（静默性能退化，非数据损坏；但退化在小数据量下完全不可观测，暴露时已难溯因）**。
- `aipersimmon-ddd-id` 是**可选**模块，五处铸造点统一采用「present 用之，absent 退回 v4」：
  - `cqrs-spring/AipersimmonDddCqrsAutoConfiguration.java:70-78`（command `messageId`）
  - `events-spring/AipersimmonDddEventsAutoConfiguration.java:60-62`（in-process `event_id`）
  - `outbox-jdbc/AipersimmonDddOutboxJdbcAutoConfiguration.java:85-87`
  - `outbox-mybatis-plus/AipersimmonDddOutboxMybatisPlusAutoConfiguration.java:120-122`
  - `operation-log-engine/AipersimmonDddOperationLogAutoConfiguration.java:80`
  - `process-manager-engine/AipersimmonDddProcessManagerAutoConfiguration.java:375-378`
- 退回路径**无 WARN、无 health 降级、无属性开关**——`getIfAvailable()` 返回 `null` 即静默走 v4。
- 与设计意图直接冲突：`core/id/IdGenerator.java` 的 Javadoc 把时间有序性写成核心契约
  （"Time ordering is what makes these ids cheap to index at scale: a monotonic key inserts near the tail
  of a B-tree instead of scattering random writes across it"）；`decision-00019` / `design-00010` 的整个立项
  理由就是消除随机 VARCHAR 主键的写放大。
- `plan-00012` 的「铁律 3（回退等价）」是**有意**引入这个 fallback 的，目的是保证 framework-free 极简装配可用。
  本 issue 不否认该目标，只指出**它当前的代价没有被标示出来**。

后果：漏引一个依赖 → 悄悄退回立项要解决的那个问题。这类退化在 10 万行时完全无感，在 500 万行时表现为
「插入越来越慢、索引膨胀」，而此时已无人记得少加了哪个依赖，且现象与原因之间没有任何可追溯的链路。

## 根因（第一性）

1. **观察 vs 期望**：期望「时间有序 id 是契约（Javadoc 如此声明）」；实际「它是一个可静默缺席的可选优化」。
2. **最小机制**：`ObjectProvider.getIfAvailable()` 的返回值 `null` 被当作「用户选择了简约装配」，而它同样
   等于「用户漏配了依赖」。**这两种意图在类型上不可区分**，代码却只为前者设计了行为。
3. **真根因**：能力降级没有被显式声明。框架在别处已经建立了正确范式——issue-00044 的
   `aipersimmonDddDurableTransportGuard` 对「传输被漏配」是 fail-loud 的。同一类问题（可选能力缺席导致语义
   退化）在 id 这里却是 fail-silent，**范式不一致**。
4. **排除的伪根因**：不是「UUIDv4 有错」。v4 全局唯一、完全合法；错的是**契约声明了时间有序、实际可能不是，
   且无从得知**。

## 复现（test-first）

`aipersimmon-ddd-cqrs-spring` 已有的双态装配测（v7/v4）证明了退回行为本身存在，**它不是要修的对象**。要补的是
「退化必须可见」：

1. `ApplicationContextRunner` 装配 `-cqrs-spring` 但 classpath 无 `IdGenerator` 实现。
2. **断言（现状 → 失败）**：上下文启动成功，且**没有**任何 WARN / 失败 / 可查询的降级标记。
3. 修复后：按所选方案——`-id` 成为传递依赖后该场景不再可达（`IdGenerator` 必然存在，断言 `version()==7`）；
   若保留可选路径，则断言启动时产生一条明确 WARN。

## 修复

采纳报告的方案 2（**把 `-id` 变成非可选依赖，删除 fallback**）：

- `-id` 目前只在 `cqrs-spring/pom.xml:80-85` 以 `test` scope 出现。改为 `compile`，并让其余四个消费模块也直接
  依赖它（或经由共同的 spring 装配模块传递）。
- 删除六处 `generator != null ? ... : () -> UUID.randomUUID()...` 三元，改为直接注入 `IdGenerator`。
- `RegistryCommandBus` / `SpringIntegrationEvents` / `OutboxWriter`（×2）的「无 supplier」构造重载随之失去
  存在理由，一并移除（破坏性，无外部使用者）。
- 保留 `IdGenerator` 作为 SPI 的可覆盖性（`@ConditionalOnMissingBean`）不变——消费方仍可换实现。

理由：JUG 是一个约 100KB、零传递依赖的库。用「五处 fallback 分支 + 一个静默正确性陷阱 + 双态测试负担」来换这
100KB 不成比例。framework-free 的承诺由 `aipersimmon-ddd-core` 承担（它仍然零依赖），而 `-cqrs-spring` 这类
装配模块本就已依赖 Spring，再依赖一个 UUID 库不改变其定位。

**注意改动面**：6 个模块的 autoconfig + 4 个 pom + 移除 4 个构造重载；**不改 schema、不改 id 语义、不改
`IdGenerator` 接口**。`plan-00012` 的「铁律 3」被本 issue 有意推翻，需在该 plan 加一条 patch 说明。

## 验证结果（已修复）

`aipersimmon-ddd-id` 已成为 6 个装配模块的 `compile` 依赖，6 处 `generator != null ? ... : UUID.randomUUID()`
三元全部删除，`RegistryCommandBus` / `SpringIntegrationEvents` / `OutboxWriter`(×2) 的默认 id 构造重载移除。

`CommandBusIdGeneratorWiringTest` 原有的 `fallsBackToUuidv4MessageId_whenIdModuleAbsent` 已改写为
`failsToStart_whenNoIdGeneratorIsAvailable`——断言缺 `IdGenerator` 时上下文**启动失败**，即降级不再静默。

框架全量 `mvn -f aipersimmon-ddd/pom.xml install` 通过，578 项测试绿。改造过程中有 3 个
`ApplicationContextRunner` 测试（`OutboxClockCoexistenceTest`、`AipersimmonDddOperationLogAutoConfigurationTest`）
因手工挑选 autoconfig 而启动失败——**这正是预期的 fail-loud**，已把 `-id` 的 autoconfig 纳入其最小装配。

## 关联

- [[report-00001-ddd-framework-review]]（P0-3，本 issue 的来源）
- [[plan-00013-phase-one-correctness-remediation]]
- [[plan-00012-time-ordered-identifiers-implementation]]（其「铁律 3：回退等价」被本 issue 推翻）
- [[decision-00019-time-ordered-uuidv7-identifiers]] / [[design-00010-time-ordered-identifiers]]
- [[issue-00054-sample-aggregate-ids-use-random-uuid]]（同源：时间有序 id 未覆盖聚合主键）
- [[issue-00044-integration-events-bypass-outbox-kafka-at-runtime]]（「可选能力缺席 → fail-loud」的正确范式对照）
