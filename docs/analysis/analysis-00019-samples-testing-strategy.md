---
id: analysis-00019-samples-testing-strategy
type: analysis
status: draft
parent: analysis-00014-ddd-samples-scenario-catalog
---

# S18 分层测试策略

对应 sample：`aipersimmon-ddd-samples/s18-testing-strategy`。场景清单见
[analysis-00014-ddd-samples-scenario-catalog](analysis-00014-ddd-samples-scenario-catalog.md)。

## 0. 本篇定位

库为测试投了两个模块（`aipersimmon-ddd-test`、`aipersimmon-ddd-test-support`）加一个架构规则模块，
但没有任何示例说明"哪一层该用哪种测试"。缺这一篇，每个 sample 会各自发明风格，读者的默认做法会是
拿 `@SpringBootTest` 测一切。

**本篇确立后续所有 sample 的测试风格**，也是它必须排在地基阶段的原因：晚写就要回头翻修。

一句话原则：**每个断言用能回答它的最便宜的测试**；容器只花在"没有真实数据库就问不出来"的地方。

## 1. 五层，各自回答什么

| 层 | 用什么 | 回答什么 | 代价 |
| --- | --- | --- | --- |
| 0 架构规则 | `aipersimmon-ddd-archunit` | 分层、构造块、乐观锁围栏 | < 1 秒，覆盖没人跑过的代码 |
| 1 领域单测 | 纯 JUnit | 不变量、状态机、值语义 | 毫秒 |
| 2 应用单测 | 库的内存替身 | 保存了什么、宣告了什么、拒绝了什么、派发了哪个命令 | 毫秒 |
| 3 边界分片 | `@WebMvcTest` + 打桩的总线 | DTO→命令的翻译、错误契约 | ~1 秒，无容器 |
| 4 集成 | `@SpringBootTest` + Testcontainers | SQL、版本谓词、真实装配 | 数秒 + 一个容器 |

sample 里 22 个测试的分布是 5 / 3 / 8 / 2 / 3——**倒金字塔的反面**：越便宜的层测得越多，最贵的
层只测那些非它不可的断言。

## 2. 层 0：架构规则先跑

```java
@AnalyzeClasses(packages = "com.example.samples.s18",
                importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
  @ArchTest static final ArchRule ddd = AiPersimmonDddRules.all();
}
```

`all()` 是 26 条规则的复合。两条是**别的测试无法替代**的：

- `versionWitnessIsAdvancedOnlyByPersistenceAdapters`——唯一阻止业务代码调 `versionAdvanced()` 把
  乐观锁解除的东西；
- `domainShouldBeFrameworkFree`——唯一让模型保持可移植的东西。

**必须排除测试类**（`ImportOption.DoNotIncludeTests`）：测试里的内存端口替身实现了 `Orders` 却住在
测试包，不排除就会打红 `implementationsShouldResideInInfrastructure`。

四条 opt-in 规则单独采纳，sample 全开了。其中一条当场改变了代码结构：
`integrationEventsShouldResideInApi` 打红后，`OrderPlaced` 从 `..application..` 移到了
`..api..`——集成事件是对外发布的契约，不是内部实现。**这就是架构规则的价值：它在没人写测试的地方
给出判断。**

另有一个源码级伴生检查（字节码看不到没有注解的 `package-info`）：

```java
PackageInfoChecks.assertEveryPackageHasPackageInfo(Path.of("src/main/java"));
```

## 3. 层 1：领域单测不要任何替身

```java
class OrderTest {
  @Test void anOrderWithoutAnAmountIsRefused() {
    assertThatThrownBy(() -> Order.place(ID, "customer-1", 0))
        .isInstanceOf(InvariantViolationException.class);
  }
}
```

没有 Spring、没有数据库、没有 mock。判断标准很硬：**如果一条领域规则需要 mock 才能测，说明规则放错
了地方**，不是测试需要帮忙。

## 4. 层 2：库给的四个替身

这一层"每秒钟买到的价值"最高：它覆盖用例自己的决定，而且是毫秒级。

| 替身 | 替换 | 能断言什么 |
| --- | --- | --- |
| `RecordingIntegrationEvents` | `IntegrationEvents` | 宣告了哪个事件，以及**消费方会收到的整个信封** |
| `RecordingCommandBus` | `CommandBus` | 派发了哪个命令、用了哪种入口、上下文长什么样 |
| `InMemoryInbox` | `Inbox` | 重投消息只生效一次 |
| `ImmediateUnitOfWork` | `UnitOfWork` | 工作被执行了几次 |

另有一个 JUnit 扩展 `@WithTenant("acme")`，把租户绑定到测试期间——sample 用它断言派发出去的命令
带上了环境里的租户，全程不需要请求、过滤器或数据库。

### 4.1 `RecordingIntegrationEvents` 建的是真信封

它不是把载荷存起来，而是构造真正的 `EventEnvelope`，于是测试能断言**消费方实际会看到什么**：

```java
assertThat(events.envelopes().get(0).type()).isEqualTo("s18.ordering.order-placed");
assertThat(events.envelopes().get(0).version()).isEqualTo(1);
assertThat(events.envelopes().get(0).tenantId()).isEqualTo(Tenants.ROOT.value());
```

副作用是好的：**事件类少了 `@EventType` 会在这里就失败**，而不是等到上线后路由不了。注意
`type()` 就是注解里的 `name`（不带版本后缀），版本是独立字段。

### 4.2 `RecordingCommandBus` 记录入口种类

它把 `send` / `send(cmd, cause)` / `sendAs` 分别记成 `ROOT` / `CHILD` / `STAGED`，并且
**`sendAs` 原样保留上下文、一个 id 都不铸造**——这正是重投幂等依赖的性质。sample 断言了
`CHILD` 派发继承 correlation、记录 causation。

### 4.3 仓储端口的替身要自己写，而且要写得笨

库不提供仓储替身——仓储端口是应用自己的词汇。sample 里的 `InMemoryOrders` 只有一个 `HashMap` 和一个
"保存过谁"的列表。**替身一旦长出行为，就变成了第二个需要维护、也会写错的实现。**

### 4.4 本篇没演示的两个（说清为什么）

`InMemoryInbox` 与 `ImmediateUnitOfWork` 在 sample 里**没有消费者**：这个示例既没有 inbox 后端，
也没有任何组件直接接 `UnitOfWork`。为了给替身造一个用途而虚构组件，不如老实说明——`InMemoryInbox`
在 S5（消费外部消息）会有真实用处；`ImmediateUnitOfWork` 适用于显式接受 `UnitOfWork` 的批处理式
组件（S11）。

## 5. 层 3：分片测试，以及它的一个真实陷阱

```java
@WebMvcTest
@ImportAutoConfiguration(AipersimmonDddWebAutoConfiguration.class)
class OrderControllerSliceTest { ... }
```

`@WebMvcTest` 只启动 web 层，两个总线打桩，于是能在一秒内回答 controller 该负责的事：请求体是否
翻译成了正确的命令、非法请求体是否变成了正确的 problem 文档。

**陷阱在第二行。** 分片只加载"与 web 相关的" auto-configuration，而**库的异常映射 advice 不在其中**。
没有这个 `@ImportAutoConfiguration`，那个 400 响应**根本没有 problem body**——`$.type` 取不到值。
只断言状态码的话，测试会通过，而错误契约完全没被验证过。

分片测试**回答不了**的问题也要写清：命令到底有没有 handler。总线是桩，而"没有 handler"或"两个
handler"是**启动期**失败，只有真实上下文能暴露。这就是端到端测试的用处，也是"每个服务一两个就够"
的理由。

## 6. 层 4：容器只花在非它不可的地方

sample 的集成测试只断言三件事，每一件都是替身给不出的：

1. **往返映射**：行到聚合、聚合到行，两个方向都对；
2. **版本谓词真的在**：两次加载、都保存，第二次必须撞
   `OptimisticLockingFailureException`——内存仓储会让这条断言在毫无保护的实现上也通过；
3. **真实装配下会发生什么**——见下。

### 6.1 写这个测试时撞到的事：完整上下文会跑起你没提到的订阅者

`theVersionPredicateIsReallyThere` 最初用金额 100 建单，结果 `first.confirm()` 直接抛
`illegal state transition: CONFIRMED -> CONFIRMED`。原因不是 bug：金额 100 低于阈值，
`AutoConfirmSmallOrders` 这个订阅者在保存时被领域事件唤醒，发了 `ConfirmOrder`，订单已经被自动
确认了。

这件事本身就是层 4 的价值，所以 sample 把它变成了一条断言
（`aSmallOrderIsAutoConfirmedByASubscriberNobodyCalled`），并把并发那条改成阈值以上的金额，让它
只测一件事。

一句话总结这条经验：**替身只做测试连接的事；真实上下文里，所有被连接的东西都会反应。** 两者都要
有，理由正在此。

### 6.2 容器怎么共享

用 `aipersimmon-ddd-test-support` 的 `@ServiceConnection` 配置类（`PostgresServiceConnection`、
`MySqlServiceConnection`、`RedisServiceConnection`、`KafkaServiceConnection`）：

```java
@SpringBootTest
@Import(PostgresServiceConnection.class)
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
```

镜像版本集中在 `ContainerImages`。共享机制是**Spring 的上下文缓存**：配置相同的测试类复用同一个
上下文，因此复用同一个容器。反过来说——**两个 `properties` 不同的测试类是两个上下文，也就是两套
容器**。S2 就付过这个代价，所以它把两个属性覆盖合并进了同一个测试类。

`@EnabledIf(DockerAvailable)` 让没有 Docker 的机器**跳过**而不是失败。代价是绿色构建可能什么都
没验证，所以每个 sample 的 README 都写了"先看有没有 skip"。

### 6.3 异步等待

集成测试遇到 outbox/流程这类异步链路时，**不要 `Thread.sleep`**：用 Awaitility 之类的轮询等待
条件成立。本篇没有异步链路，所以不做演示——真实例子在 S4 与 S9。

## 7. 判断一个断言该放哪一层

问三个问题，第一个"是"就停下：

1. **只涉及领域对象的状态与规则吗？** → 层 1。
2. **只涉及"用例调用了哪个端口"吗？** → 层 2（用替身）。
3. **必须有真实的 SQL、真实的装配或真实的并发才能成立吗？** → 层 4。

剩下的边界翻译归层 3。**在层 4 重复层 1 和层 2 已经覆盖的断言，是构建时间最常见的浪费来源。**

## 8. 常见错法

| 错法 | 后果 |
| --- | --- |
| 用 `@SpringBootTest` 测领域规则 | 每条断言多花数秒，失败信息还更差 |
| 架构规则的导入没排除测试类 | 测试里的内存替身把规则打红 |
| 从不采纳架构规则 | 乐观锁围栏与"领域无框架"两条保证根本不存在 |
| `@WebMvcTest` 里不导入 web autoconfiguration | 只断言状态码会过，problem 契约其实没被验证 |
| 把内存替身写得很聪明 | 它变成第二个实现，会以自己的方式出错 |
| 每个测试类加一组不同的 `properties` | 每组多一套容器，构建时间线性增长 |
| 用 `Thread.sleep` 等异步 | 慢且不稳 |
| 只在集成测试里覆盖用例决策 | 反馈慢，且真实上下文的副作用会掩盖你想测的东西 |

## 9. 本篇不覆盖

- 领域怎么建模（S16）、聚合怎么落表（S17）——本篇只测它们；
- 异步链路的等待与断言（S4 / S9）；
- 契约测试与多版次共存（S21）；
- 端到端测试的完整形态（S1 已有一个）；
- 性能与压测。
