---
id: issue-00063-in-memory-web-store-cannot-be-built-when-several-clocks-exist
type: issue
status: resolved
parent: plan-00015-scaffold-depth-and-evaluability
---

# 开启幂等即启动失败：内存兜底注入裸 `Clock`，而框架自己装了 5 个 `Clock`

## 问题（现状，file:line 为证）

- **等级：High（"开一个开关就起不来"，且失败信息完全不指向真凶）**。
- 触发：在样例（ordering + inventory + payment，装了 outbox / inbox / process-manager /
  operation-log）里设 `aipersimmon.ddd.web.idempotency.enabled=true`，启动失败：

  ```
  Error creating bean with name 'aipersimmonDddIdempotencyStore':
    Failed to instantiate [com.aipersimmon.ddd.web.spi.IdempotencyStore]:
    Factory method 'aipersimmonDddIdempotencyStore' threw exception with message:
    No qualifying bean of type 'java.time.Clock' available:
    expected single matching bean but found 5
  ```

- 兜底 bean（`aipersimmon-ddd-web-spring-boot-starter/.../AipersimmonDddWebAutoConfiguration.java:147-149`）：

  ```java
  public IdempotencyStore aipersimmonDddIdempotencyStore(ObjectProvider<Clock> clock) {
    return new InMemoryIdempotencyStore(clock.getIfAvailable(Clock::systemUTC));
  }
  ```

- 而框架自己按组件各装一个 `Clock`，同类型共 **7 处**声明（本应用命中 5 个）：
  `process-manager-engine/.../AipersimmonDddProcessManagerAutoConfiguration.java:79`、
  `outbox-mybatis-plus/...:67`、`outbox-jdbc/...:64`、`inbox-mybatis-plus/...:31`、`inbox-jdbc/...:28`、
  `operation-log-engine/...:31`、`web-store-jdbc/...:26`。

## 根因（第一性）

1. **观察 vs 期望**：期望"没有 `Clock` bean 就用系统时钟"；实际"有多个 `Clock` bean 时直接抛异常"。
2. **最小机制**：`ObjectProvider.getIfAvailable()` 只在**一个都没有**时返回 `null`；候选多于一个时它抛
   `NoUniqueBeanDefinitionException`。**返回 `null` 的那个方法是 `getIfUnique()`。**
   代码想表达"可选依赖"，用的却是"必须唯一"的取值方式。
3. **真根因**：`getIfAvailable` 与 `getIfUnique` 的语义差被当成同义词使用。兜底的意图是
   "有人给就用、没人给就默认"，在存在多个候选时唯一正确的表达是 `getIfUnique`。
4. **为什么在库自己的测试里没暴露**：库的每个模块单测各自只装配自己那一个组件，
   容器里 `Clock` 至多一个，`getIfAvailable` 与 `getIfUnique` 行为一致。
   **只有把多个组件装在一起的应用才会命中**——也就是任何真实应用。样例是第一个把它们装齐的消费者。
5. **放大因素（值得单独记一笔）**：框架按组件各注册一个**未加限定符**的 `Clock`，
   于是应用里任何一处注入裸 `Clock` 都是歧义的——包括使用方自己的代码。
   `-web-store-jdbc` 用的是有名字的 `aipersimmonDddWebStoreClock`（`:26`），是对的写法；
   其余六处不是。这不属于本 issue 的最小修复，但它是同一个坑的土壤。
6. **排除的伪根因**：不是 `-web-store-jdbc` 没引入（引入了，见
   [[issue-00062-web-store-module-does-not-displace-the-in-memory-stores]]，它没能顶掉兜底才让兜底被构造）；
   不是 `Clock` bean 缺失（恰恰相反，太多）。

## 复现（test-first）

一个装了两个及以上带 `Clock` 组件的上下文 + `aipersimmon.ddd.web.idempotency.enabled=true`
且无 `IdempotencyStore` 后端，即必然失败。样例的 `OrderIdempotencyTest` 就是它：三个用例
全部以 `Failed to load ApplicationContext` 报错，根因如上。

## 修复

```java
clock.getIfUnique(Clock::systemUTC)
```

`ReplayGuard` / `RateLimiter` 的兜底若有同样写法，一并改。

修完后本 issue 与 issue-00062 相互独立：00062 决定"该不该用兜底"，00063 决定"兜底本身能不能造出来"。
两个都要修——只修 00062，兜底仍是一颗留给"没装后端模块"的使用者的哑弹。

## 验证结果

已修。四处 `clock.getIfAvailable(Clock::systemUTC)` →
`clock.getIfUnique(Clock::systemUTC)`（`AipersimmonDddWebAutoConfiguration.java:148/186/208/229`，
即 idempotency store、replay guard、replay filter、rate limiter 的兜底）。

- **回归守卫**：`InMemoryStoreClockResolutionTest`（`-web-spring-boot-starter`），两个用例——
  "多个 Clock 时兜底仍能装配"与"一个 Clock 都没有时仍退回系统时钟"。
- **先红后绿**：把这一行改回 `getIfAvailable` 单独重跑，`severalClocksDoNotBreakTheFallbackStores`
  立刻失败于
  `No qualifying bean of type 'java.time.Clock' available: expected single matching bean but found 2:
  outboxClock,processManagerClock` —— 与样例上报的"found 5"同一失败，只是候选少了三个。
- **全量**：库 `install` 1528 项全绿；样例 `verify` 330 项全绿，`OrderIdempotencyTest` 四个用例通过。
- **未一并处理（已在 §5 记录）**：框架仍按组件注册 7 个**未加限定符**的 `Clock`。本次只保证兜底不再因此
  炸掉；使用方代码里任何一处注入裸 `Clock` 依然是歧义的。这属于另一个决定（给它们加限定符或收成一个 bean），
  留给 plan-00015 的摩擦点清单。

## 关联

- [[issue-00062-web-store-module-does-not-displace-the-in-memory-stores]]（同一次触发暴露；先后关系见上）
- [[issue-00058-in-memory-web-stores-are-a-silent-multi-instance-trap]]（D2 把 in-memory 定位为"开发默认"，
  本缺陷让这个默认在真实应用里不可用）
- [[plan-00015-scaffold-depth-and-evaluability]]（F2 因此受阻）
