---
id: issue-00092-each-test-context-starts-its-own-container-pair
type: issue
role: main
status: open
parent: report-00002-scaffold-ddd-review
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

部分已修（保持 open）。

- **已做（修复第 1、3 条）**：`README.md` 的 Build and run 一节说明了
  `mvn verify` 会为每个不同的测试上下文各起一对容器、以及这换来的是什么，
  并给出了不需要 Docker 的快路径（三个 `*-domain` 模块的单测，秒级）。
  `TestInfrastructure` 的 javadoc 把"哪些因素会让上下文分裂"写在了公共位置——
  此前只有 `SelfCancelTest` 的一句局部注释提过。
- **未做（修复第 2 条的 JUnit tag 分组，以及复现一节的上下文计数断言）**：
  快路径目前靠 `-pl` 列模块，比 tag 粗糙；计数断言可防止容器数量无声增长，仍值得补。
- 验证：README 与 javadoc 改动无行为影响；`spotless:check` 通过。

## 关联

- [[report-00002-scaffold-ddd-review]]
- [[issue-00067-test-support-covers-every-store-except-the-transport]]（`TestInfrastructure` 的由来）
- [[issue-00060-scaffold-tests-set-a-process-manager-prefix-that-does-not-bind]]（同一批 `properties` 块的上一轮问题）
