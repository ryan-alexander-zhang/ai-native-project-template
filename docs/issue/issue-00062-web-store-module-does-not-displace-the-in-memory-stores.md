---
id: issue-00062-web-store-module-does-not-displace-the-in-memory-stores
type: issue
status: resolved
blocks: [plan-00015-scaffold-depth-and-evaluability]
---

# 加了 `-web-store-jdbc` 也不一定生效：两个自动配置对同一个 bean 没有先后关系

> **注（2026-08-06 补）**：本记录写于库同时并存 JDBC 与 MyBatis-Plus 两套存储后端的时期。
> `-persistence-jdbc`、`-outbox-jdbc`、`-inbox-jdbc`、`-process-manager-jdbc`、`-operation-log-jdbc`、
> `-web-store-jdbc`、`-starter-jdbc` 已全部删除（库只留 MyBatis-Plus 后端；web 边界存储由
> `-web-store-mybatis-plus` 承接）。因此下文带 `-jdbc` 的模块名、路径与 `file:line`，指的是当时的代码，
> 不是现在的树；它们作为当时的证据保留，未被改写成 MyBatis-Plus 的路径。

## 问题（现状，file:line 为证）

- **等级：High（能力静默不生效——正是 issue-00044 治过的那一类，只是这次在 web 侧未被治；
  在本仓当前依赖组合下它还会直接导致启动失败，见 [[issue-00063-in-memory-web-store-cannot-be-built-when-several-clocks-exist]]）**。
- 触发场景（真实操作，不是构造出来的）：样例按 `CHOOSING-MODULES.md` 与 `CONFIGURATION.md` 的指引
  1. 加依赖 `aipersimmon-ddd-web-store-jdbc`；
  2. `aipersimmon.ddd.flyway.components` 补 `web-store`；
  3. `aipersimmon.ddd.web.idempotency.enabled=true`。

  期望：幂等走 `JdbcIdempotencyStore`。实际：容器装配的是 **`InMemoryIdempotencyStore`**。
- 两个自动配置都声明同一个 bean，且都用 `@ConditionalOnMissingBean`，**彼此之间没有任何顺序声明**：
  - `aipersimmon-ddd-web-spring-boot-starter/.../AipersimmonDddWebAutoConfiguration.java:46`
    —— `@AutoConfiguration(after = JacksonAutoConfiguration.class)`；
    `:141-149` 声明 `IdempotencyStore aipersimmonDddIdempotencyStore(...)`，条件是
    `@ConditionalOnMissingBean(IdempotencyStore.class)`。
  - `aipersimmon-ddd-web-store-jdbc/.../AipersimmonDddWebStoreJdbcAutoConfiguration.java:21`
    —— `@AutoConfiguration(after = JdbcTemplateAutoConfiguration.class)`；同样以
    `@ConditionalOnMissingBean` 声明 `IdempotencyStore`（以及 `ReplayGuard`、`RateLimiter`）。
- 两者都只相对**第三方**自动配置排序，彼此之间的相对次序因此是未定义的。谁先被评估，谁的
  `@ConditionalOnMissingBean` 就先成立，另一个就永远不装。

## 根因（第一性）

1. **观察 vs 期望**：期望"引入后端模块 ⇒ 后端实现替换掉内存兜底"；实际"两个候选者按未定义的顺序竞争，
   兜底可能先到"。
2. **最小机制**：`@ConditionalOnMissingBean` 的判定发生在**该自动配置被处理的那一刻**，看的是
   "此刻容器里有没有"。它表达的是"没人提供就由我兜底"，**不表达优先级**。要表达"后端优先于兜底"，
   必须有一条显式的排序边（`@AutoConfiguration(before = …)` / `beforeName`），否则两者之间没有 happens-before。
3. **真根因**：兜底与实现被写成了两个**平级**的 `@ConditionalOnMissingBean` 提供者。这在只有一个提供者时
   看不出问题，一旦第二个出现，正确性就依赖装配顺序这个偶然量。
4. **这不是新问题，是同一个问题的第二次出现**：[[issue-00044]] 里 `events-spring` 的
   in-process 发布器盖掉了 outbox 的持久化发布器，修法正是给 outbox 侧加
   `@AutoConfigure(beforeName = …)` + 一个 fail-loud 守卫。web 侧的兜底/后端对完全同构，但没有做同样的事。
5. **为什么没有任何告警**：`AipersimmonDddWebAutoConfiguration:269-313` 的 D2 守卫检查的是
   "当前的 store 是不是 in-memory 实现"，它**确实会**在这种情况下报警——但它报的是
   "你没装后端模块"，而使用者明明装了。守卫的诊断在这个场景下指向错误的补救措施。
6. **排除的伪根因**：不是 BOM 缺条目（`aipersimmon-ddd-bom/pom.xml:227` 有）；不是
   `AutoConfiguration.imports` 缺失（`aipersimmon-ddd-web-store-jdbc` 的 imports 文件存在且内容正确）；
   不是 `@ConditionalOnBean(JdbcTemplate)` 不满足（同一应用里 `JdbcTemplate` 存在，
   `OutboxAtomicityTest` 等一直在用）。

## 复现（test-first）

在 `start`（已依赖 `-web-store-jdbc`、已开启 `idempotency.enabled=true`）断言装配结果：

```java
@Test
void theJdbcStoreDisplacesTheInMemoryOne() {
  assertInstanceOf(JdbcIdempotencyStore.class, context.getBean(IdempotencyStore.class));
}
```

当前失败——容器里是 `InMemoryIdempotencyStore`（在本仓的依赖组合下，实际连
`InMemoryIdempotencyStore` 都造不出来，启动即失败，见 issue-00063；那是另一个根因，
但两者是同一次触发暴露的）。

## 修复

按 issue-00044 已确立的做法，在**后端侧**声明优先：

```java
@AutoConfiguration(
    after = JdbcTemplateAutoConfiguration.class,
    beforeName = "com.aipersimmon.ddd.web.spring.AipersimmonDddWebAutoConfiguration")
```

用 `beforeName` 而非 `before`，是因为后端模块不应对 `-web-spring-boot-starter` 产生编译依赖
（同 issue-00044 的处理）。`-web-store-redis` 需要同样处理。

守卫的措辞也应一并修正：它今天把"in-memory 生效"一律归因为"你没装后端模块"。

## 验证结果

已修。`-web-store-jdbc` 与 `-web-store-redis` 各加一条
`beforeName = "com.aipersimmon.ddd.web.spring.AipersimmonDddWebAutoConfiguration"`。

- **回归守卫落在样例，不在库里**：`aipersimmon-ddd-web-store-jdbc` 按设计**不依赖**
  `-web-spring-boot-starter`，所以库内没有任何一个上下文同时装得下这两个自动配置——
  用 `ApplicationContextRunner` 断言排序就得先加一条测试期依赖边，为一条断言改依赖图不划算。
  样例是两者唯一共存的地方，守卫因此放在
  `OrderIdempotencyTest#theSharedStoreIsTheOneInUse`（断言活跃的 `IdempotencyStore`
  是 `JdbcIdempotencyStore`），沿用 `OutboxAtomicityTest` 断言"活跃的 `IntegrationEvents`
  必须是持久化实现"的同一手法。
- **修复前**：样例开启幂等后装配的是内存兜底（并因 issue-00063 直接启动失败）。
- **修复后**：`mvn -f aipersimmon-ddd/pom.xml install` —— 1528 项测试全绿；
  `mvn -f aipersimmon-ddd-scaffold/multi-module/pom.xml verify` —— BUILD SUCCESS，330 项全绿。
- **未一并处理**：D2 守卫的措辞仍把"in-memory 生效"一律归因为"你没装后端模块"。排序修好后
  这个误导的触发条件已经消失（装了后端就一定顶掉兜底），故不在本 issue 内改。

## 关联

- [[issue-00044]]（同构缺陷的第一次出现，已修；本 issue 是它在 web 侧的未修副本）
- [[issue-00058-in-memory-web-stores-are-a-silent-multi-instance-trap]]（D2 的守卫；本缺陷让它的诊断指错方向）
- [[issue-00063-in-memory-web-store-cannot-be-built-when-several-clocks-exist]]（同一次触发暴露的第二个缺陷）
- [[plan-00015-scaffold-depth-and-evaluability]]（F2 因此受阻）
