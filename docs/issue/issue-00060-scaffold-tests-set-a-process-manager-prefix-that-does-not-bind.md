---
id: issue-00060-scaffold-tests-set-a-process-manager-prefix-that-does-not-bind
type: issue
status: resolved
blocks: [report-00001-ddd-framework-review]
---

# 样例 11 个测试类设置的 process-manager 配置键不存在，被静默忽略

> **注（2026-08-06 补）**：本记录写于库同时并存 JDBC 与 MyBatis-Plus 两套存储后端的时期。
> `-persistence-jdbc`、`-outbox-jdbc`、`-inbox-jdbc`、`-process-manager-jdbc`、`-operation-log-jdbc`、
> `-web-store-jdbc`、`-starter-jdbc` 已全部删除（库只留 MyBatis-Plus 后端；web 边界存储由
> `-web-store-mybatis-plus` 承接）。因此下文带 `-jdbc` 的模块名、路径与 `file:line`，指的是当时的代码，
> 不是现在的树；它们作为当时的证据保留，未被改写成 MyBatis-Plus 的路径。

## 问题（现状，file:line 为证）

- **等级：Medium（不产生红灯，但样例是使用者抄写的对象；它现在教的是一条无效的配置键，
  而且让 11 个测试类的调度控制全部失效）**。
- 样例侧全部 11 个 `@SpringBootTest` 类都写着同一对属性，例如
  `aipersimmon-ddd-scaffold/multi-module/start/src/test/java/com/example/OrderingFlowTest.java:45-46`：

  ```java
  "aipersimmon.ddd.process-manager.jdbc.effect-relay.poll-delay=200ms",
  "aipersimmon.ddd.process-manager.jdbc.deadline-worker.poll-delay=1h",
  ```

  另外 10 处：`ExceptionContractTest:33-34`、`ReviewFlowTest:29-30`、
  `IntegrationEventTransportTest:41-42`、`MybatisPlusInterceptorCompositionTest:37-38`、
  `ConcurrentAggregateWriteTest:44-45`、`OutboxAtomicityTest:36-37`、`TwoTenantAcceptanceTest:43-44`、
  `AggregateIdIsTimeOrderedTest:37-38`、`PaymentCompensationFlowTest:52-53`、
  `OperationLogRecordingTest:34-35`。
- 实际前缀不含 `jdbc`：
  `aipersimmon-ddd-process-manager-engine/.../ProcessManagerProperties.java:13` 是
  `@ConfigurationProperties(prefix = "aipersimmon.ddd.process-manager")`，其下只有
  `effectRelay` / `deadlineWorker` 两个 `Worker`（`:39-40`），**没有 `jdbc` 这一层**。
- 库自己的测试用的是正确前缀，例如
  `aipersimmon-ddd-process-manager-jdbc/.../ProcessManagerJdbcAutoConfigurationTest.java:37`：
  `aipersimmon.ddd.process-manager.effect-relay.poll-delay=1h`。

后果：11 个测试类里"把 relay 调到 1h 让它别打扰"和"把 relay 提速到 200ms 让流程快点跑完"
**两种意图都没有生效**，实际一律运行在默认 `500ms`（`ProcessManagerProperties.Worker` 的默认值）。
测试仍然全绿，但绿的原因与它们声明的原因不是同一个——这正是最难在下一次改动中察觉的那类失效。

## 根因（第一性）

1. **观察 vs 期望**：期望"`@SpringBootTest(properties=…)` 里写的键会改变 relay 的轮询节奏"；
   实际"该键不对应任何绑定目标，被 Spring 静默丢弃"。
2. **最小机制**：`@ConfigurationProperties` 默认 `ignoreUnknownFields = true`
   （`ProcessManagerProperties.java:13` 未关闭它），未知键既不报错也不告警；relaxed binding 只做
   大小写/分隔符归一化，**不会**把 `jdbc.effect-relay` 折叠成 `effectRelay`。于是这行属性等于没写。
3. **真根因不在样例，在库的注释**：`ProcessManagerProperties.java:10` 的 Javadoc 至今写着
   "Configuration for the JDBC Process Manager runtime, under
   `aipersimmon.ddd.process-manager.jdbc`" —— **与同一文件第 13 行的 `prefix=` 自相矛盾**。
   process-manager 拆成 `-engine` + `-jdbc` / `-mybatis-plus` 后端时前缀去掉了 `jdbc` 段，
   `prefix=` 改了，Javadoc 没改。样例是照着 Javadoc 抄的，所以错在同一处。
4. **为什么没被任何门禁拦住**：Spring Boot 的配置元数据校验（`spring-boot-configuration-processor`
   产出的 `additional-spring-configuration-metadata`）只在 IDE 侧提示，不参与 `verify`；
   未知属性在运行期无声。整条链路上没有一处会因为"写了不存在的键"而失败。
5. **排除的伪根因**：不是后端模块另有一套 `.jdbc` 前缀的属性类——全仓
   `@ConfigurationProperties` 前缀清单里只有 `aipersimmon.ddd.process-manager` 一条，
   没有任何 `.jdbc` 变体。

## 复现（test-first）

确定性复现，无需并发或计时：在 `start` 模块新增

```java
@SpringBootTest(properties = "aipersimmon.ddd.process-manager.effect-relay.poll-delay=1h")
class ProcessManagerPropertyBindingTest {
  @Autowired ProcessManagerProperties properties;

  @Test
  void theDocumentedPrefixBinds() {
    assertEquals(Duration.ofHours(1), properties.getEffectRelay().getPollDelay());
  }
}
```

把属性换成样例当前使用的 `aipersimmon.ddd.process-manager.jdbc.effect-relay.poll-delay=1h`，
断言得到默认的 `500ms` 而非 `1h` —— 这就是今天 11 个测试类所处的状态。

## 修复

1. `[process-manager-engine]` 改正 `ProcessManagerProperties.java:10` 的 Javadoc（去掉 `.jdbc`），
   消灭根因；这是唯一的库侧改动。
2. `[scaffold]` 11 个测试类的 22 行属性去掉 `jdbc.` 段。
3. `[scaffold]` 留下上面的绑定测试作为回归守卫——它会在任何一次前缀漂移时变红。

## 验证结果

修复提交 `079dbef`。

- **先红**：`BackgroundWorkerControlTest` 按上文写好后，先用样例当时的旧前缀跑一次——
  `theProcessManagerPrefixBinds` 失败于
  `expected: <PT1H> but was: <PT0.5S>`，即"键被丢弃、取到 500ms 默认值"，与根因分析一致。
- **后绿**：前缀改正（库侧 Javadoc + 11 个测试类的 22 行）后，
  `mvn -f aipersimmon-ddd-scaffold/multi-module/pom.xml verify` **BUILD SUCCESS**，
  测试计数 314 → 320（新增守卫，既有断言一行未改）；
  `mvn -f aipersimmon-ddd/pom.xml -pl aipersimmon-ddd-process-manager-engine install` 绿。
- **静态验证**：`grep -r --include=*.java 'process-manager\.jdbc\.'` 在全仓 0 命中，
  仅守卫测试的类注释里保留一处对旧前缀的说明性引用。
- **回归守卫**：`BackgroundWorkerControlTest.WhenTheWorkersAreTurnedOff#theProcessManagerPrefixBinds`
  ——前缀若再次漂移，它会立刻变红。

## 关联

- [issue-00061-scaffold-tests-disable-the-outbox-relay-with-the-wrong-lever](issue-00061-scaffold-tests-disable-the-outbox-relay-with-the-wrong-lever.md)（同一批测试类里的另一处
  调度控制失效，根因不同）
- [plan-00015-scaffold-depth-and-evaluability](../plan/plan-00015-scaffold-depth-and-evaluability.md)（批次 E1）
