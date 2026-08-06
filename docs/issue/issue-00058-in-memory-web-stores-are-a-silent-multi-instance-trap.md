---
id: issue-00058-in-memory-web-stores-are-a-silent-multi-instance-trap
type: issue
status: resolved
blocks: [report-00001-ddd-framework-review]
---

# 幂等/重放/限流的 in-memory 默认实现在多实例下静默失效：一个只在 Javadoc 里警告的生产陷阱

## 问题（现状，file:line 为证）

- **等级：High（不丢数据，但让三条明确开启的**安全/正确性**保护在多实例下静默失去效力；开启者以为自己被保护着）**。
- `AipersimmonDddWebAutoConfiguration` 在三处以 in-memory 实现兜底，且只在
  `@ConditionalOnProperty(... havingValue = "true")` 即**使用者明确开启该能力**时才装配：
  - `:133-141` `IdempotencyStore` → `InMemoryIdempotencyStore`
  - `:170-178` `ReplayGuard` → `InMemoryReplayGuard`
  - `:213-221` `RateLimiter` → `InMemoryRateLimiter`
- 三个实现的 Javadoc 都诚实写着 "Not suitable for multiple instances"
  （如 `InMemoryIdempotencyStore.java:14-17`），但**这是唯一的警告**：无 WARN、无 health 降级、无开关。
- 后果按能力分别是：
  - **幂等**：两个实例各存一份 `Idempotency-Key`。同一个键打到不同实例 → 重复提交**直接穿透**，
    副作用执行两次。使用者恰恰是为了阻止这件事才开启它的。
  - **重放保护**：nonce 各存一份 → 同一个已签名请求在另一个实例上可**重放成功**。这是安全控制失效。
  - **限流**：每实例独立计数 → 实际速率是配置值 × 实例数。

## 根因（第一性）

1. **观察 vs 期望**：期望「开启幂等 = 重复提交被阻止」；实际「开启幂等 = 在单实例上被阻止，在多实例上不被阻止，
   且两者无法区分」。
2. **最小机制**：`@ConditionalOnMissingBean(IdempotencyStore.class)` 把「使用者选了后端」与「使用者忘了选后端」
   压成同一个分支。缺 bean 时代码只为前者设计了行为（装个能跑的），没有为后者产生任何信号。
   **这与 [[issue-00053-id-generator-silently-degrades-to-uuidv4]] 的 `ObjectProvider.getIfAvailable()` 是同一个
   反模式**：两种意图在类型上不可区分。
3. **真根因**：能力降级没有被显式声明。而框架在别处**已经建立了正确范式两次**——
   [[issue-00044-integration-events-bypass-outbox-kafka-at-runtime]] 的 `aipersimmonDddDurableTransportGuard`
   对「传输被漏配」fail-loud；[[issue-00057-unlimited-systemic-retry-is-invisible]] 的 `SystemicStallReporter`
   对「无限重试」持续报告。in-memory web store 是**同一模式的第三个实例，仍是 fail-silent**。
4. **排除的伪根因**：
   - 不是「in-memory 实现有 bug」。它们在单实例下完全正确，作为开发默认值是**对的**——体验重要，
     不该要求本地起 Redis。要修的是「它在生产被静默使用」。
   - 不是「Javadoc 写得不够清楚」。Javadoc 已经写明了；问题是**没人在装配时读 Javadoc**。
   - 不是「该默认关闭这三个能力」。它们本来就默认关闭（`enabled = false`），是使用者主动开启的——
     这让问题更严重：主动开启表达了明确意图。

## 复现（test-first）

`ApplicationContextRunner` 装配 `-web-spring-boot-starter`，设 `aipersimmon.ddd.web.idempotency.enabled=true`，
classpath 上无任何 `-web-store-*`：

1. **断言（现状 → 失败）**：上下文启动成功，`IdempotencyStore` 是 `InMemoryIdempotencyStore`，
   且**没有任何 WARN**、没有任何可查询的降级标记。
2. 修复后：
   - 默认仍启动成功，但产生一条点明「哪个能力降级了、多实例下会怎样、加哪个模块」的 WARN；
   - 置 `aipersimmon.ddd.web.allow-in-memory-stores=false` 时**启动失败**，异常消息列出降级的能力与补救方式。

## 修复

采纳报告 P1-6 的两条建议中的**两者结合**，但**不做 health indicator**：

- **默认 WARN**：一个 `SmartInitializingSingleton` 守卫，在所有单例就绪后检查「哪些已开启的能力正由 in-memory
  实现承担」，逐条 WARN，写明失效方式与补救模块。放在装配完成后而非 bean 方法内，是为了让报告一次性覆盖三条能力，
  而不是散成三条互不相干的日志。
- **可令其 fail-loud**：新增 `aipersimmon.ddd.web.allow-in-memory-stores`，默认 `true`（开发体验优先）。
  生产 profile 置 `false` → 启动失败。这与 issue-00044 的 durable-transport guard 是同一形状。
- **不做 `/actuator/health` degraded**：那需要给 `-web-spring-boot-starter` 引入 `spring-boot-actuator`
  依赖（当前没有），而它提供的信息与启动期 WARN/失败完全重合。多一个可选依赖、多一处需要理解的机制，
  换不到新信息。**这是有意省略，不是遗漏。**

## 验证结果（已修复）

`AipersimmonDddWebAutoConfiguration.aipersimmonDddInMemoryStoreGuard`（`SmartInitializingSingleton`）+
新属性 `aipersimmon.ddd.web.allow-in-memory-stores`（默认 `true`）。

新增 `InMemoryStoreGuardTest` 六项测试（`ApplicationContextRunner` + `OutputCaptureExtension`）：

- 单个能力降级 → WARN 同时点名**失效方式**（"the side effect runs twice"）与**补救模块**
  （`aipersimmon-ddd-web-store-redis`）；
- 三个能力同时降级 → **一条**消息报出 `3 enabled concern(s)`，而不是三条互不相干的日志；
- 未开启的能力不报（不制造噪声）；
- 消费方自带 store bean → 守卫**静默**（真后端就是修复本身）；
- `allow-in-memory-stores=false` → **启动失败**，消息含 `allow-in-memory-stores=false` 与降级能力名；
- 同时置 `false` 且提供真 store → 仍正常启动（该开关否决的是**回退**，不是能力本身）。

其中「置 `false` 则启动失败」这项测试**在守卫不存在时必然失败**（上下文会正常启动），因此不可能是恒真断言。

框架全量 `install`（全质量门）通过；样例 `verify` BUILD SUCCESS。样例未开启这三项能力，故不产生 WARN——
无需改动样例配置。

**有意未做**：`/actuator/health` degraded。它需要给 `-web-spring-boot-starter` 新增 `spring-boot-actuator`
依赖，而提供的信息与启动期 WARN/失败完全重合。

## 关联

- [[report-00001-ddd-framework-review]]（P1-6）
- [[plan-00014-adoption-threshold-and-architecture-simplification]]（D2）
- [[issue-00053-id-generator-silently-degrades-to-uuidv4]]（同一反模式：两种意图类型上不可区分）
- [[issue-00044-integration-events-bypass-outbox-kafka-at-runtime]]（fail-loud 守卫的形状来源）
- [[issue-00057-unlimited-systemic-retry-is-invisible]]（同一「降级必须显式声明」结论的第二例）
