---
id: issue-00092-each-test-context-starts-its-own-container-pair
type: issue
status: resolved
blocks: [report-00002-scaffold-ddd-review]
---

# 每个不同的测试上下文都会另起一对容器，且这件事没有写在任何地方

## 问题（现状，file:line 为证）

- **等级：Low（不是缺陷——它换来了很好的隔离性；但代价没有被记录，而 `mvn verify` 是本项目对外承诺的质量门）**。
- 容器是 Spring **bean**，不是静态字段（`PostgresServiceConnection.java:16-20`、
  `KafkaServiceConnection.java:30-34`）：

```java
@Bean @ServiceConnection
PostgreSQLContainer<?> postgresContainer() { return new PostgreSQLContainer<>(ContainerImages.POSTGRES); }
```

  ⇒ 生命周期由 Spring 上下文管理，**每一个不同的应用上下文各拿一对新容器**。
- `start` 模块里的 22 个测试类落在若干个**不同**的上下文配置上——
  区分因素包括 `properties`、`webEnvironment`、`@AutoConfigureMockMvc`、
  `@MockitoBean`，以及嵌套的 `@TestConfiguration`：

  | 上下文特征 | 测试类 |
  |---|---|
  | 三 worker 200ms + 无内嵌配置 | `ReviewFlowTest`、`TwoTenantAcceptanceTest`、`IntegrationEventTransportTest`、`OperationLogRecordingTest` |
  | 同上 + `RecorderConfig` | `OrderingFlowTest` / `PaymentCompensationFlowTest`（各自不同的 Recorder ⇒ 再分裂成两个） |
  | 同上 + `@AutoConfigureMockMvc` | `ExceptionContractTest` |
  | 同上 + `@MockitoBean` + `PT2S` | `PaymentTimeoutFlowTest` |
  | 三 worker 关闭 + RANDOM_PORT | `SelfCancelTest`、`OrderIdempotencyTest`、`OrderListPagingTest`、`DeadLetterReplayTest` |
  | 同上 + `StageTheRace` | `ConcurrentApprovalTest` |
  | 三 worker 关闭（无 web） | `OutboxAtomicityTest`（+`FailInsideTransaction`）、`ConcurrentAggregateWriteTest`、`MybatisPlusInterceptorCompositionTest`、`AggregateIdIsTimeOrderedTest` |
  | 默认 + `@Nested` 两组 `@TestPropertySource` | `BackgroundWorkerControlTest`（再分裂） |
  | 纯默认 | `ApplicationSmokeTest` |

  粗估 **9–11 对 PostgreSQL + Kafka 容器**，每对都要启动、跑 Flyway
  （5 个组件的迁移 + 3 个业务迁移）、建 topic、再销毁。

- **隔离性因此很好，而且被利用了**：`OutboxAtomicityTest:70-73` 敢断言
  `select count(*) from ordering.orders == 0`，正是因为它独占一个数据库；
  `ConcurrentAggregateWriteTest` 也能安心用 `SKU-CONC-<nanoTime>` 之外的全局状态。
  这不是巧合，是这个结构给的。
- **但代价没有出现在任何地方**：`README.md:24-25` 只写
  "`mvn verify` … Needs Docker."，没有提这会拉起十来对容器、
  也没有给一条"只跑快测试"的路径。第一次跑的人会以为构建卡住了。
- 一个相关的隐藏耦合：几个测试类之所以**必须**保持 `properties` 完全一致才能共享上下文，
  在 `SelfCancelTest.java:33-34` 有注释提过一句
  （"The properties match `OrderIdempotencyTest` and `OrderListPagingTest` exactly,
  so all three share one application context and one pair of containers"）——
  **只有这一处说了**，其它类都没有。也就是说，有人随手给某个类加一条 property，
  就会静默多出一对容器，而没有任何东西会提示他。

## 根因（第一性）

1. **观察 vs 期望**：期望"知道 `mvn verify` 大概要花多久、为什么"；
   实际"耗时由一个不可见的上下文缓存键决定"。
2. **最小机制**：Spring TestContext 的缓存键包含
   properties、`webEnvironment`、`@Import`、嵌套配置类、bean override 等；
   键不同即另起上下文，而容器是上下文里的 bean ⇒ 键不同即另起容器。
   **这个键从不被打印出来。**
3. **真根因**：容器的生命周期被绑到了"上下文"这个粒度上，
   而上下文的分裂是由**测试的编写方式**决定的、且不可见。
   于是"这个项目要起多少容器"不是一个被设计的数字，而是一个涌现的结果。
4. **排除的伪根因**：不是应该改成 `static` 容器 + 手工 `@DynamicPropertySource`——
   那会退回全局共享数据库，`OutboxAtomicityTest` 的全局计数断言就不再成立，
   隔离性换成了速度。**当前选择是对的**；缺的只是把它说出来，以及给一条加速路径。

## 复现（test-first）

没有失败测试可写。用一条可观测断言把容器数量钉住，防止它无声增长：

```java
@Test
void theNumberOfDistinctTestContextsIsDeliberate() {
  // 扫 start/src/test 下所有 @SpringBootTest 的上下文特征（properties / webEnvironment /
  // 嵌套 @TestConfiguration / @MockitoBean），聚合成 key 集合
  Set<String> contexts = distinctContextKeys();
  assertEquals(EXPECTED_CONTEXTS, contexts.size(),
      "新增了一个测试上下文 ⇒ 多一对容器。若是有意的，请更新此处并在 README 记一笔：\n"
      + String.join("\n", contexts));
}
```

## 修复

不改结构，只做三件事：

1. **在 README 记下来**。`Build and run` 一节补一句：
   `mvn verify` 会为每个不同的测试上下文各起一对 PostgreSQL + Kafka 容器
   （当前约 N 对），这是刻意的——它换来的是每个上下文都能对整库做断言。
2. **给一条快路径**：`mvn -pl ordering/ordering-domain,inventory/inventory-domain,payment/payment-domain test`
   跑纯单测（无 Docker，秒级），或用 JUnit tag 分开 `unit` / `acceptance`。
   一个 scaffold 应该让人**能只跑快的那部分**。
3. **把上下文共享的约定写进 `TestInfrastructure` 的 javadoc**——
   它现在写的是容器从哪来（`:19-23`，很好），
   补一句"`properties` 块完全一致的类共享一对容器；改动它会多起一对"。
   `SelfCancelTest:33-34` 已经有这句话的一个局部版本，把它上提到公共位置。

## 验证结果

已修。

- **已做（修复第 1、3 条）**：`README.md` 的 Build and run 一节说明了
  `mvn verify` 会为每个不同的测试上下文各起一对容器、以及这换来的是什么，
  并给出了不需要 Docker 的快路径（三个 `*-domain` 模块的单测，秒级）。
  `TestInfrastructure` 的 javadoc 把"哪些因素会让上下文分裂"写在了公共位置——
  此前只有 `SelfCancelTest` 的一句局部注释提过。
- **已做（复现一节的计数断言，本轮补上）**：`TestContextCountTest`。

  **关键实现选择：不自己推算缓存键，直接问 Spring 要。**
  原稿设想的是"扫 `properties` / `webEnvironment` / 嵌套 `@TestConfiguration` / `@MockitoBean`
  聚合成 key"——那等于手写一份 Spring 缓存键的近似实现，而这个近似**一定会漂**：
  真正的键还包含 context customizer、bean override、`@ActiveProfiles`、
  `@DynamicPropertySource` 的存在与否等等，Spring 每个版本都可能再加。

  实际做法：`MergedContextConfiguration` **就是**那个缓存键
  （Spring 的 `ContextCache` 按它查表），而
  `BootstrapUtils.resolveTestContextBootstrapper(clazz).buildMergedContextConfiguration()`
  能把它构造出来而**不启动任何上下文**。测试跑完 1.1 秒，不起容器。
  测试类的枚举复用 ArchUnit（`ArchitectureTest` 已在用），
  并显式带上 `@Nested` 内部类——它们继承外层的 `@SpringBootTest`，
  却能靠 `@TestPropertySource` 再分裂出自己的上下文（`BackgroundWorkerControlTest` 正是如此）。

- **一个实测结论，与 issue 原稿的估计不符**：**实际是 17 个不同上下文，不是原稿粗估的 9–11 个。**
  原稿那张表是靠读测试源码归纳的，漏掉了若干分裂因素。
  这个差距本身就是"必须算而不能估"的最好论据，已写进测试的 javadoc。
  17 个里 16 个各带一对容器；例外是 `ProductionProfileBootTest`——
  它从 `SharedContainers` 取裸容器且完全不起 broker（理由在它自己的 javadoc 里）。
  README 的数字已从"roughly a dozen"改成实测的 17/16。

  断言失败时打印的是**分组**（哪些测试类共享一个上下文），不是一个数字，
  所以读的人能直接看出新上下文是从哪一组里分裂出去的。

- **负向对照（实测）**：给 `SelfCancelTest` 的 `properties` 加一条
  `spring.application.name=negative-control`，计数从 17 变 18，
  且分组里 `SelfCancelTest` 从原来那组
  （`OrderListPagingTest` / `ReadmeQuickstartTest` / `DeadLetterReplayTest` / `OrderIdempotencyTest`）
  里单独裂了出来 —— 正是本 issue 描述的"随手加一条 property 就静默多一对容器"。
  改回后恢复 17。（对照用备份文件还原，不是 `git checkout --`。）

- **仍未做（有意的）**：修复第 2 条里的 **JUnit tag 分组**（`unit` / `acceptance`）。
  快路径目前仍靠 `-pl` 列三个 `*-domain` 模块。tag 会更细，但它要求给几十个测试类逐个打标签，
  收益相对 `-pl` 那条现成命令有限，且与本 issue 的核心（容量不可见）无关。
  单开一条更合适，不再挡着本 issue。
- 验证：README 与 javadoc 改动无行为影响；`spotless:check` 通过；
  `mvn -o test -pl start -am -Dtest=TestContextCountTest` 绿。

## 关联

- [[report-00002-scaffold-ddd-review]]
- [[issue-00067-test-support-covers-every-store-except-the-transport]]（`TestInfrastructure` 的由来）
- [[issue-00060-scaffold-tests-set-a-process-manager-prefix-that-does-not-bind]]（同一批 `properties` 块的上一轮问题）
