---
id: report-00001-ddd-framework-review
type: report
status: active
---

对 `aipersimmon-ddd` 组件框架的一次整体评审：从**正确性、易用性、可用性、可扩展性**四个角度给出问题定级与改造方案，目标是让它成为中小型企业可落地的 DDD 开发框架。

评审基线：分支 `lang/java/ddd`，`50a37c3`。`mvn -o -T1C -DskipTests install` 全绿（40 个模块）。

---

## 0. 总体判断

**这个框架的工程质量明显高于绝大多数自研 DDD 脚手架。** 值得保留的部分：

- **概念纪律**。`CONTEXT.md` 定义的统一语言（Process Manager / Business Step / Operation Outcome vs Transaction Completion / Tenant vs Actor）是真正做对的事——多数框架在这里就崩了。Javadoc 不是复述签名，而是解释"为什么这样设计、不要用成什么"。
- **34 条 ArchUnit 规则**（`aipersimmon-ddd-archunit`）把分层、事件流向、聚合建模、错误码约定变成可执行断言。这是框架能长期不腐烂的关键资产。
- **`OutboxRelay`** 的 per-subject 顺序保持 + 永久/瞬时失败分类 + 死信搬移 + ShedLock 单实例轮询，考虑得比多数商业实现细。
- **`ProcessDefinition` 是纯函数**（`(state, input) → decision`），按 `step` 而不是按 input 类型分派、乱序事实走 `ignore` 而不抛异常——这是 at-least-once 语义下唯一正确的写法。
- 消息身份治理（`send` / `sendAs`、`publish` / `publishAs`）把"谁铸造 id"这件事收口得非常干净。

**核心诊断：框架的"内核质量"远超它的"产品化程度"。**

三个层面的失衡：

| | 现状 | 问题 |
|---|---|---|
| 内部设计文档 | 18,674 行 / 150 篇 | 极其充分 |
| 框架主代码 | 27,173 行 / 40 模块 | 合理 |
| 面向使用者的文档 | **0 行** | `README.md` 仍是模板原文，`ARCHITECTURE.md` 是未填写的占位模板 |

对中小企业而言，采纳成本几乎全部落在"产品化"这一侧，而不是内核能力上。下面按优先级展开。

---

## P0 — 正确性缺口

### P0-1 聚合没有乐观锁，一致性边界没有被真正强制 ★最严重

DDD 里聚合的定义就是"一个事务一致性单元"。当前框架**在任何一层都没有提供聚合版本号机制**：

- `core/model/AbstractAggregateRoot.java` — 只有 `domainEvents` + `checkInvariant`，**没有 `version` 字段**。
- `core/model/Entity.java` — 只有 `id()`。
- 样例 DDL `start/.../V1__aggregates.sql` 的 `ordering.orders` 表：`(id, customer_id, status)`，**没有 version 列**。
- 样例仓储 `MyBatisOrders.save()` 是裸的读-改-写：

```java
if (orders.selectById(id) == null) { orders.insert(header); } else { orders.updateById(header); }
```

后果，两个都能在生产上真实发生：

1. **丢失更新 / 状态机被绕过**。`ConfirmOrder` 与 `CancelOrder` 并发到达同一订单：两者各自 `findById` 拿到 `CONFIRMED_PENDING`，各自通过 `OrderLifecyclePolicy` 的转换检查（各自看到的都是合法前态），各自 `updateById`。后写入者胜出，**聚合内部辛苦守护的 `Transitions` 保证在聚合外被静默破坏**。更糟的是两条命令都"成功"了，两份领域事件都发了出去，下游流程管理器会收到互相矛盾的事实。
2. **`selectById` + `insert` 的 TOCTOU**。并发首次保存同一 id 会撞主键约束，抛出的是 `DuplicateKeyException`，而不是干净的冲突语义。

**并且冲突处理链路目前是死代码**：`ConcurrencyTranslationCommandInterceptor` 专门捕获 `OptimisticLockingFailureException` 翻译成 `ConcurrencyConflictException`（→ HTTP 409），但**框架里没有任何一处会对聚合抛出这个异常**。整条"冲突→409"的设计存在，却永远不会被触发。

> 对比：流程管理器自己做得很对——`ProcessRevision` + `StaleProcessRevisionException` + `ProcessInstanceStore` 的版本化写入。**能力已经有了，只是没有给业务聚合用。**

**方案（建议重写 `AbstractAggregateRoot`，破坏性变更）**

```java
public abstract class AbstractAggregateRoot<ID> implements AggregateRoot<ID> {
  private final transient List<DomainEvent> domainEvents = new ArrayList<>();
  private long version;   // 0 = 尚未持久化

  /** 加载时由仓储回填。 */
  protected final void restoreVersion(long persisted) { this.version = persisted; }
  public final long version() { return version; }
  /** 保存成功后由仓储推进。 */
  public final long nextVersion() { return version + 1; }
}
```

配套三件事，缺一不可：

1. `ordering.orders` 等聚合表加 `version BIGINT NOT NULL DEFAULT 0`。
2. 仓储写入改为版本化 UPDATE，`0 行受影响` → 抛 `OptimisticLockingFailureException`（Spring 的 `JdbcTemplate`/MyBatis-Plus `@Version` 都能做）：
   ```sql
   UPDATE ordering.orders SET status = ?, version = version + 1
    WHERE id = ? AND version = ?
   ```
   同时把 `selectById + insert/update` 改成先 `INSERT ... ON CONFLICT DO NOTHING` 判断新建，或直接以 `version == 0` 区分新建/更新，消掉 TOCTOU。
3. 新增 ArchUnit 规则：`aggregateRootsShouldBeSavedWithVersionCheck()` —— 让"忘记加乐观锁"在编译期/测试期就暴露，而不是在生产事故里暴露。这条规则比前两条更重要，因为它保证**每一个后续新增的聚合**都不会漏。

同时给 `-jdbc` / `-mybatis-plus` 各提供一个 `AbstractVersionedRepository` 基类，让业务方默认就走在正确路径上。

### P0-2 `save` + `publishAndClear` 两步手工调用 —— 领域事件静默丢失

当前约定（`PlaceOrderHandler`）：

```java
orders.save(order);
domainEvents.publishAndClear(order);   // 忘了这行 → 事件静默消失，无任何报错
```

这是典型的"正确性依赖开发者记忆"。事件丢失没有任何信号：测试可能仍然通过（如果没断言事件），线上表现为流程管理器不推进、投影不更新——最难排查的一类 bug。而且 `DomainEvents` 的 Javadoc 明确把这个责任推给调用方（"a repository (or the handler) calls..."），**"or" 意味着两处都可能忘**。

**方案（推荐 A）**

- **A（推荐，收口到仓储）**：在 `-jdbc` / `-mybatis-plus` 提供的仓储基类的 `save()` 末尾自动 `publishAndClear`。业务仓储继承基类即得，handler 里那行删掉。配 ArchUnit 规则禁止 handler 直接调 `publishAndClear`，把入口收成唯一一个。
- **B（兜底防御，可与 A 叠加）**：在 `TransactionCommandInterceptor` 提交前检查本次事务内所有被 `save` 过的聚合是否仍有未清空的 `domainEvents`，有则 **fail loud**。哪怕未来有人绕过基类，也会立刻炸而不是静默丢事件。

B 的价值在于：它把"静默丢失"变成"启动即失败"，符合框架其他地方（如 outbox 传输选择的 fail-loud guard）已经建立的风格。

### P0-3 `IdGenerator` 缺失时静默降级为 UUIDv4

`aipersimmon-ddd-id` 是可选模块，5 个铸造点全部是这个模式：

```java
generator != null ? generator::newId : () -> UUID.randomUUID().toString();
```

`IdGenerator` 的 Javadoc 把时间有序性说成是核心契约（"Time ordering is what makes these ids cheap to index at scale"），而 `decision-00019` 的整个立项理由就是消除随机 VARCHAR 主键的 B-tree 写放大。**忘记加一个依赖，就悄悄退回到立项要解决的那个问题**，并且没有任何日志、没有任何启动告警。

对中小企业尤其危险：这类问题在 10 万行数据时完全无感，在 500 万行时表现为"插入越来越慢"，而此时已无人记得少加了哪个依赖。

**方案**：三选一，按侵入性递增

1. 启动时 `log.warn` 明确说明后果与补救依赖（最小改动，但 warn 常被忽略）；
2. 把 `aipersimmon-ddd-id` 变成 `-cqrs-spring` 的**非 optional** 依赖，删掉所有 fallback 分支——它只依赖 JUG 一个轻量库，没有理由可选；
3. 保留可选，但通过 `aipersimmon.ddd.id.require-time-ordered=true`（默认 `true`）在缺失时**启动失败**。

推荐 **2**：一个 100KB 的依赖不值得用 5 处 fallback 分支 + 一个静默正确性陷阱来换。

### P0-4 旗舰样例的聚合 ID 用 `UUID.randomUUID()`，与框架设计意图正面冲突

`PlaceOrderHandler`：

```java
OrderId orderId = new OrderId(UUID.randomUUID().toString());
```

`ordering.orders.id` 是 `VARCHAR(64) PRIMARY KEY` —— **这正是 `IdGenerator` 立项要治的那个"随机 VARCHAR 主键"**，而且聚合表的行数通常比 outbox/process 表高一个数量级，收益最大的恰恰是这里。

`IdGenerator` 的 Javadoc 列了排除项（`tenant_id`、web 幂等键、`requestId`、`WorkerId`），**聚合 ID 不在排除列表里**，所以这不是"设计上不适用"，而是样例没跟上。

样例是使用者第一个抄的东西。它现在教的是错的写法。

**方案**：把 `IdGenerator` 注入 handler（或给 `OrderId` 一个 `OrderId.next(IdGenerator)` 工厂），并在 `IdGenerator` 的 Javadoc 里把"业务聚合主键"**显式列为推荐用途**。这条改完顺手能给 P0-1 的 version 列腾出正确的表结构基线。

---

## P1 — 易用性 / 可用性（决定能不能被中小企业采纳）

### P1-1 没有聚合 starter：一个标准应用要手工拼 17 个依赖 ★最大采纳阻碍

`start/pom.xml` 里的 aipersimmon 依赖清单：

```
-id  -events-spring  -cqrs-spring  -operation-log-cqrs-spring  -operation-log-mybatis-plus
-web-spring  -openapi-spring-boot-starter  -process-manager-mybatis-plus  -flyway
-outbox-mybatis-plus  -inbox-mybatis-plus  -messaging-kafka
-tenancy-spring  -tenancy-mybatis-plus  -observability-otel-spring-boot-starter
-archunit(test)  -test-support(test)
```

使用者必须**预先知道**：`-cqrs-spring` 和 `-events-spring` 是两件事、operation-log 要同时加 `-cqrs-spring`（采集）和 `-mybatis-plus`（存储）两个、tenancy 要同时加 `-spring`（Web 边界）和 `-mybatis-plus`（SQL 改写）两个、outbox 存在就必须配 `-flyway` 或自己抄 DDL。**这套知识目前只存在于内部设计文档里。**

中小企业团队的现实是：没有人会读 150 篇设计文档来搞清楚该加哪 17 个依赖。这一条不解决，其他所有优点都无法兑现。

**方案：引入按"技术栈组合"聚合的 starter，把 17 降到 2–3**

```
aipersimmon-ddd-spring-boot-starter               # cqrs + events + web + id + observability(no-op)
aipersimmon-ddd-mybatis-plus-spring-boot-starter  # 上面 + outbox/inbox/process/operation-log/tenancy 的 mp 后端 + flyway
aipersimmon-ddd-jdbc-spring-boot-starter          # 同上，jdbc 后端
aipersimmon-ddd-messaging-kafka-spring-boot-starter
aipersimmon-ddd-observability-otel-spring-boot-starter  # 已有
```

于是 `start/pom.xml` 变成：

```xml
<dependency>…<artifactId>aipersimmon-ddd-mybatis-plus-spring-boot-starter</artifactId></dependency>
<dependency>…<artifactId>aipersimmon-ddd-messaging-kafka-spring-boot-starter</artifactId></dependency>
```

细粒度模块全部保留不动（可扩展性不受损，高级用户仍可精确挑选）；starter 只是**默认路径**。所有组件已经是 `@ConditionalOnMissingBean` / `@ConditionalOnClass` 风格，聚合 starter 是纯 pom 工作 + 少量 `@AutoConfiguration` 顺序声明，**投入产出比在整个评审里最高**。

### P1-2 模块命名违反自己定的规则，且 `-spring` / `-spring-boot-starter` 两套并存

根 pom 明确写着："Only the pluggable *-spring / *-jpa / messaging starter modules are permitted to depend on Spring."

实际违反者（名字不带 `-spring` 却编译期依赖 Spring）：

| 模块 | 依赖 |
|---|---|
| `aipersimmon-ddd-outbox` | `spring-context`, `spring-boot-autoconfigure`, `jackson-databind` |
| `aipersimmon-ddd-id` | `spring-context`, `spring-boot-autoconfigure` |
| `aipersimmon-ddd-operation-log-engine` | `spring-context`, `spring-boot-autoconfigure` |
| `aipersimmon-ddd-process-manager-engine` | `spring-tx`, `spring-boot-autoconfigure` |

同时后缀有两套习惯：`-cqrs-spring` / `-tenancy-spring` / `-web-spring` / `-events-spring` / `-saga-spring` 用 `-spring`，而 `-observability-otel-spring-boot-starter` / `-openapi-spring-boot-starter` 用 `-spring-boot-starter`。使用者无法从名字推断"哪个是纯契约、哪个带自动配置"。

**方案**：确立三段式命名并全量对齐（现在没有外部使用者，是唯一的免费重命名窗口）

- `aipersimmon-ddd-<domain>` — 纯契约，**零 Spring**，可被 domain 层依赖
- `aipersimmon-ddd-<domain>-<backend>` — 存储/传输适配（`-jdbc` / `-mybatis-plus` / `-kafka` / `-redis`）
- `aipersimmon-ddd-<domain>-spring-boot-starter` — 带 `AutoConfiguration.imports` 的装配层

按此规则：`-outbox` 的 Spring 部分拆到 `-outbox-spring-boot-starter`，留下零依赖的 `-outbox`（`OutboxMessage` / `OutboxDispatcher` / `FailureClassifier` 契约）；`-cqrs-spring` → `-cqrs-spring-boot-starter`；`-id` → `-id` + `-id-spring-boot-starter`。**规则一旦统一，就用 ArchUnit/Enforcer 断言它**（例如"artifactId 不含 `spring` 的模块不得出现 `org.springframework` 依赖"），否则半年后会再次漂移。

### P1-3 inbox / outbox 的对称性被打破

- outbox：契约在 `aipersimmon-ddd-outbox`（`OutboxMessage`、`OutboxDispatcher`…）
- inbox：契约 `Inbox.java` 在 **`aipersimmon-ddd-application`**，而 `aipersimmon-ddd-inbox` 模块**只有 DDL、没有一行 Java**

`-inbox/pom.xml` 里有一段注释为此辩解（"the inbox has no storage-agnostic core with contracts"），但结果是使用者要在两个完全不同的地方找两个对称概念，且 `-inbox` 这个名字承诺了代码却只有 SQL。

**方案**：把 `Inbox` 接口移入 `aipersimmon-ddd-inbox`，与 outbox 对称。顺带把 `Inbox` 从 `aipersimmon-ddd-application` 移走——`application` 模块应该只放**通用应用层契约**（异常、事件端口），而不是某个具体基础设施组件的端口。

### P1-4 流程管理器的 codec 手写成本过高，而样例走了最难的那条路 ★

样例一条 7 步流程的成本：

```
OrderFulfilmentDefinition.java   290 行   ← 业务逻辑，合理
OrderFulfilmentCodecs.java       282 行   ← 纯序列化样板
OrderFulfilmentState/Input        97 行
RuntimeOrderFulfilmentProcess    113 行
                        合计     804 行
```

那 282 行 codec 是手写的 ``（unit separator）分隔字符串编解码，**按位置**取字段：

```java
s -> new StockReserved(parts(s)[0], parts(s)[1]);
```

这个写法很脆：加一个字段就要同步改 encode/decode 两侧，字段顺序错了不报错只是数据串位，`parts(s)` 还被重复调用两次。

**关键问题是：框架其实已经提供了 Jackson 便捷层，样例却没用。** `JacksonProcessCodecConfiguration` + `ProcessSerializationCatalog` 只要声明一个 catalog bean：

```java
@Bean ProcessSerializationCatalog fulfilmentCatalog() {
  return ProcessSerializationCatalog.builder()
      .payload("ordering.fulfilment.stock-reserved", 1, StockReserved.class)
      .payload("ordering.fulfilment.payment-declined", 1, PaymentDeclined.class)
      // … 8 条
      .state(OrderFulfilmentDefinition.PROCESS_TYPE, new StateSchemaVersion(1),
             new PayloadType("ordering.fulfilment.state", 1), OrderFulfilmentState.class)
      .build();
}
```

**282 行 → 约 15 行。**

也就是说，框架的旗舰样例让流程管理器看起来比实际难 20 倍。任何评估这个框架的人看到 804 行/条流程都会直接放弃。这是纯粹的样例问题，**不需要改框架代码**。

**方案**：
1. 样例改用 `ProcessSerializationCatalog`（默认路径）；
2. 保留一份手写 codec 作为**加密/升级(upcasting)场景的进阶示例**，并在注释里说明"仅当需要加密、upcasting 或非 JSON 格式时才这样写"；
3. 在文档里把两条路径的选择标准写清楚。

### P1-5 缺少面向使用者的文档

`README.md` 是模板原文（"This repository is a docs-first template…"），`ARCHITECTURE.md` 是**完全未填写的占位模板**（还留着 `[e.g., React, Next.js]`、`[Insert Project Name]`）。150 篇设计文档全部是内部决策记录（`decision-00019`、`design-00009`…），它们回答"我们当初为什么这么选"，不回答"我该怎么用"。

对中小企业采纳来说，这一条和 P1-1 是同一个问题的两面。

**方案**：补四份文档，其他 150 篇都不用动

1. **`README.md`（框架版）** — 5 分钟能跑起来的最小示例：一个聚合 + 一条命令 + 一个 HTTP 端点，2 个依赖。
2. **`ARCHITECTURE.md`** — 用真实内容替换占位模板：模块分层图（pure / contract / backend / starter 四层）、依赖方向规则、40 个模块的一句话职责表。
3. **"选择指南"** — 决策树式的：单体还是多上下文？要不要跨服务事件（→ outbox+kafka）？要不要跨聚合长流程（→ process-manager）？要不要多租户？每个"要"对应加哪个 starter。
4. **配置参考** — `aipersimmon.ddd.*` 全部配置项、默认值、影响。目前这些只散落在 `@ConfigurationProperties` 的 Javadoc 和样例 `application.yml` 的注释里（样例注释写得很好，但不成体系）。

### P1-6 In-memory 默认实现的多实例陷阱

`AipersimmonDddWebAutoConfiguration` 在缺省时装配 `InMemoryIdempotencyStore` / `InMemoryReplayGuard` / `InMemoryRateLimiter`。Javadoc 诚实标注了"Not suitable for multiple instances"，但**默认装配 + 只在 Javadoc 里警告**，等于把一个静默失效的生产陷阱设为默认值：两个实例下幂等键各存一份，重复提交直接穿透。

**方案**：保留 in-memory 作为开发默认（体验重要），但当 `aipersimmon.ddd.web.idempotency.enabled=true` 且 classpath 上没有 `-web-store-*` 时，**启动打 WARN 并在 `/actuator/health` 暴露 degraded**。或者更彻底：加 `aipersimmon.ddd.web.allow-in-memory-stores`（默认 `true`，生产 profile 置 `false` 则启动失败）。这与 P0-3 是同一类问题，建议用同一套"能力降级必须显式声明"的机制统一处理。

---

## P2 — 架构 / 可扩展性

### P2-1 `cqrs` → `integration` 依赖方向倒置

`aipersimmon-ddd-cqrs` 依赖 `aipersimmon-ddd-integration`，唯一原因是 `CommandContext` 上的这个便利方法：

```java
public static CommandContext of(EventEnvelope<?> envelope) { … }
```

方向错了：`EventEnvelope` 是**集成/传输层**概念（CloudEvents 线格式），`CommandContext` 是**写侧核心**概念。现在的结果是任何只想用命令总线的项目都被迫拖进整套集成事件契约；而 `application` 模块又同时依赖 `core` + `integration` + `cqrs`，三者纠缠。

**方案**：把这个方向反过来。删掉 `CommandContext.of(EventEnvelope)`，在 `integration` 侧提供

```java
// aipersimmon-ddd-integration
public final class EventEnvelopes {
  public static CommandContext toCommandContext(EventEnvelope<?> e) { … }
}
```

依赖变成 `integration → cqrs`（集成层知道写侧，写侧不知道集成层），`cqrs` 只保留 `core` + `tenancy`。这是纯机械重构，唯一的调用点在入站适配器。

> 附带收益：`cqrs` 变轻之后，"只要 CQRS 不要消息"这个非常常见的中小项目场景才真正成立。

### P2-2 删除 `aipersimmon-ddd-saga` / `-saga-spring`（已确认废弃）

这两个模块目前仍在反应堆 `<modules>` 和 BOM 里，仍会被构建和发布。`saga` 包里有一个 `@ProcessManager` 注解，与 `aipersimmon-ddd-process-manager` 的整套概念**同名冲突**；`CONTEXT.md` 自己写着 "_Avoid_: Saga when naming the toolkit's generic coordinator"。同时 `SagaState` 有 `version` 字段和乐观锁语义，而业务聚合没有（P0-1）——保留它只会让人误以为这是推荐路径。

**方案**：直接删除两个模块目录，从根 pom `<modules>` 和 BOM 中移除。既然废弃，不需要 `@Deprecated` 过渡期（无外部使用者）。

### P2-3 流程管理器的概念负载对中小企业偏重

`-process-manager` 48 个类 + `-engine` 64 个类 = **112 个类**，暴露给使用者的概念包括 `ProcessRevision`、`StateSchemaVersion`、`DefinitionVersion`、`ProcessClaimStrategy`、`WorkerId`、`ProcessPayloadCodecRegistry`、`ProcessStateCodecRegistry`、`ParkedInput`、`ProcessBacklog`…

这些概念本身都有正当理由（版本化状态迁移、多 worker 抢占、有界重试、可观测性），实现质量也高。但对一个"3 步审批流"的中小企业场景，这是**过度工程**——而 3 步审批流恰恰是中小企业最常见的需求。

**方案：不要削弱引擎，而是加一层"薄门面"**，把 80% 场景的概念暴露面压到 5 个以内：

```java
// 只需要 (state, input) → next；版本化/租户/租约/重试/codec 全部按约定默认
public interface SimpleProcess<S> {
  ProcessType type();
  S start(ProcessInput first);
  S next(S state, ProcessInput input, Effects effects);   // effects.dispatch(cmd) / effects.deadline(...)
}
```

默认约定：`DefinitionVersion("v1")`、`StateSchemaVersion(1)`、Jackson codec 自动按类型注册（复用 P1-4 的 catalog 机制）、终态由 `Effects.complete(outcome)` 声明。需要多版本共存、加密、自定义抢占策略时再"下沉"到完整 `ProcessDefinition`。

这是**渐进式披露**（progressive disclosure）：默认简单，复杂度按需付费。同一思路也适用于 operation-log（39+32+20 个类）。

### P2-4 `core` 缺少一些高频建模件，导致每个项目重复造

`core` 只有 `AggregateRoot` / `Entity` / `Identifier` / `Association` / `Invariant` / `Transitions` / `ErrorCode`。样例里自己造了 `Money`（`ordering-domain/shared/Money.java`）——但金额是**所有**企业应用都要的，而且是最容易做错的（浮点、币种混算、舍入）。

同时缺失：
- `Identifier` 只是空标记接口，没有基类，每个 ID 类型都要重写 `value()` / `equals` / `hashCode` / 校验。样例里 `OrderId` / `CustomerId` / `TenantId` 各写一遍。
- 没有 `ValueObject` 基类或 `Specification`（`Invariant` 是断言式的，缺少可组合的决策式判定）。
- `Page` / `Slice` / `Cursor` 在 `aipersimmon-ddd-web` —— 分页是**读模型**概念，把它放在 web 模块意味着 application 层想返回分页结果就得依赖 web。

**方案**：
- `core` 增加 `AbstractIdentifier`（record 友好的基类或一个 `@Identity` 校验工具）+ 可选的 `aipersimmon-ddd-money` 模块（或直接引 Joda-Money / `javax.money`，不必自造）；
- 把 `Page` / `Slice` / `Cursor` 从 `-web` 下沉到 `-cqrs`（读侧契约的正确归属），`-web` 只保留它们的 HTTP 序列化；
- `Specification<T>` 加到 `core/rule`，与 `Invariant` 并列——`Invariant` 回答"违反了就抛"，`Specification` 回答"符不符合"，两者都需要，现在只有前者。

### P2-5 `AbstractAggregateRoot` 缺少基于身份的 `equals` / `hashCode`

`Entity` 的 Javadoc 明确写着"Two entities are equal when their identities are equal, not when their attribute values match"——但 `AbstractAggregateRoot` **没有实现 `equals`/`hashCode`**，于是默认是引用相等，直接违反自己文档声明的契约。把同一个订单从仓储加载两次放进 `Set` 会得到两个元素。

**方案**：在 `AbstractAggregateRoot` 实现基于 `id()` 的 `equals`/`hashCode`（注意用 `getClass()` 而非 `instanceof` 以避免跨类型相等），并加 ArchUnit 规则禁止子类覆写。这个改动很小但属于正确性范畴，只是影响面比 P0-1 小。

---

## 实施建议：三个阶段

按"先止血、再降门槛、后精简架构"排序。P0 与 P1-1/P1-4 是必做项，其余可按资源取舍。

### 阶段一：正确性止血（建议优先，1–2 周）

| 项 | 内容 | 影响面 |
|---|---|---|
| P0-1 | 聚合乐观锁：`version` 字段 + 版本化仓储基类 + DDL + **ArchUnit 规则** | core / 两个 backend / 样例 / DDL |
| P0-2 | 仓储基类自动 `publishAndClear` + 事务提交前 fail-loud 检查 | backend / cqrs-spring |
| P0-3 | `-id` 转为非可选依赖，删除 5 处 UUIDv4 fallback | cqrs-spring / events-spring / outbox×2 / operation-log / process-manager |
| P0-4 | 样例聚合 ID 改用 `IdGenerator`；Javadoc 补"聚合主键"为推荐用途 | 样例 / core Javadoc |
| P2-5 | 基于身份的 `equals`/`hashCode` | core |

阶段一结束后，框架在"聚合是一致性边界"这个 DDD 最核心的承诺上才算真正成立。

### 阶段二：采纳门槛（1–2 周，投入产出比最高）

| 项 | 内容 |
|---|---|
| P1-1 | 3–4 个聚合 starter，17 依赖 → 2 依赖（纯 pom 工作） |
| P1-4 | 样例改用 `ProcessSerializationCatalog`，282 行 → 15 行（不改框架代码） |
| P1-5 | 四份使用者文档：README / ARCHITECTURE / 选择指南 / 配置参考 |
| P1-6 | in-memory 降级显式化（与 P0-3 共用一套机制） |

阶段二是**唯一能让前面所有工程质量被外部看见**的阶段。P1-4 尤其廉价——它只是改样例，却直接决定别人第一眼觉得这个框架是"20 分钟上手"还是"800 行起步"。

### 阶段三：架构精简（2–3 周，趁无外部使用者）

| 项 | 内容 |
|---|---|
| P2-2 | 删除 `saga` / `saga-spring` |
| P2-1 | 反转 `cqrs` ↔ `integration` 依赖方向 |
| P1-2 | 统一三段式模块命名 + Enforcer/ArchUnit 断言 |
| P1-3 | `Inbox` 契约移入 `-inbox`，与 outbox 对称 |
| P2-4 | `Page`/`Slice`/`Cursor` 下沉到 `-cqrs`；补 `Specification`、`AbstractIdentifier` |
| P2-3 | `SimpleProcess` 薄门面（渐进式披露） |

命名统一（P1-2）**必须在发布 Maven archetype 之前完成**——这是最后一个免费重命名窗口。

---

## 实施结果

三个阶段全部完成。阶段一见 [[plan-00013-phase-one-correctness-remediation]]，阶段二与三见
[[plan-00014-adoption-threshold-and-architecture-simplification]]（其「完成记录」逐项列出结果与 11 处偏差）。

本报告的判断在实施中被推翻或修正的四处，**以 plan-00014 与 design-00012 的记录为准**：

- **P1-2 的三段式规则不可照字面执行**（会把 42 个模块变成约 60 个）；真正的不变量是「领域层可依赖的模块
  必须零 Spring」，据此真违规者只有 `-outbox` 一个 → [[design-00012-module-naming-and-spring-freedom]]。
- **P2-1 的转换点不应放在 `integration`**（那会把它从零依赖的根变成非根）；已放在 `application`。
- **P2-4 的 `AbstractIdentifier` 不可实现**：所有 id 类型都是 record，record 免费提供 `equals`/`hashCode`
  且不能继承类。
- **P2-3 的 `SimpleProcess` 门面不应建**：会放弃纯函数形状并造成一个概念两套 API；改为在
  `ProcessDefinition` 上给版本化方法加默认值。

## 附：不建议改的部分

评审中确认这些是对的，不要"顺手优化"掉：

- **`ProcessDefinition` 的纯函数签名和 `ignore` 语义**。乱序/重复事实返回 no-op 而不抛异常，是 at-least-once 下唯一正确的做法，注释里也解释了它修掉的三个具体误行为。
- **`send` / `sendAs`、`publish` / `publishAs` 的双入口**。看似冗余，实际是"谁铸造消息身份"的关键区分，且用 ArchUnit 规则守住了业务代码不误用。
- **`OutboxRelay` 的 per-subject 顺序保持子查询**。相关子查询在大表上有成本，但语义正确性优先；真出现性能问题时加 `(subject, sent, next_attempt_at, created_at)` 复合索引即可，不要改语义。
- **34 条 ArchUnit 规则**。这是框架的护城河，应该继续加（本报告就建议新增 3 条：聚合版本化写入、禁止 handler 直调 `publishAndClear`、非 `-spring` 模块禁止依赖 Spring）。
- **`CONTEXT.md` 的统一语言纪律**，以及 Operation Log 的 `Outcome × Completion` 二维正交设计——后者是很多审计日志实现踩坑的地方。
