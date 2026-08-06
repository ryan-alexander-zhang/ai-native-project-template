---
id: issue-00099-tenant-isolation-fails-open-below-the-edge
type: issue
status: resolved
blocks: [report-00003-ddd-library-review-2026-07-29]
---

# 边缘之下租户隔离全线 fail-open：丢一次绑定就静默读写 `__root__` 共享桶

## 问题（现状，file:line 为证）

- **等级：Critical（数据隔离失效，且失效时无任何信号）**。
- 「当前线程没有绑定租户时用什么」这个决策，被**复制到了 14 个调用点**，每一处的答案都是"回退哨兵"：

```java
// 完全相同的一行，出现在 9 个模块的 14 处
TenantContext.current().map(TenantId::value).orElse(Tenants.ROOT.value())
```

  `RegistryCommandBus:69`、`TenantContextTenantLineHandler:37`、`JdbcInbox:55`、`MybatisPlusInbox:53`、
  `DefaultProcessQuery:64`、`AipersimmonDddOperationLogCqrsAutoConfiguration:89`、
  `Jdbc{IdempotencyStore:90,RateLimiter:94,ReplayGuard:57}`、`Redis{IdempotencyStore:61,RateLimiter:37,ReplayGuard:27}`、
  `InMemory{IdempotencyStore:65,RateLimiter:63,ReplayGuard:43}`。
- `KafkaIntegrationEventListener:135,200` 是同一决策的第二种形态：`ce_tenantid` 缺失即回退哨兵。
- **`MissingTenantException` 是死代码**：全树 grep，除自身定义外零引用——它的 javadoc 说自己在
  `MissingTenantPolicy#REJECT` 下抛出，而 `MissingTenantPolicy` 只在 `TenantResolutionFilter` 一处被咨询。
  也就是说：**策略只管边缘，边缘之下无策略**。
- 后果（`tenancy.enabled=true` 时）：任何到不了边缘绑定的执行路径——`@Async`、`CompletableFuture` 回调、
  调度线程、Kafka 消费线程、忘写 `runAs` 的批处理——都会让 MyBatis-Plus 拦截器改写出
  `WHERE tenant_id = '__root__'`：
  - `SELECT` 静默返回空，**与"该租户没有数据"无法区分**；
  - `INSERT` 落进哨兵桶，而在迁移过的部署里哨兵桶装的是迁移前的生产数据。
- 反讽的是 `TenancyMybatisPlusProperties.tenantTables` 的 javadoc **把这个陷阱写下来了**
  （"would be narrowed to the root sentinel and silently return nothing"）——文档记录了它，而不是阻止它。
- 同一份代码对缺失 `IdGenerator` 是启动即失败（`CommandBusIdGeneratorWiringTest`），
  对缺失 durable outbox 是启动即失败（`DurableIntegrationEvents` + fail-loud 守卫）。
  **唯独对缺失租户是静默兜底**——风险姿态自相矛盾。

另外三处同源缺陷，一并在本 issue 修掉：

1. **默认解析器可伪造**（`HeaderTenantResolver` + `AipersimmonDddTenancyAutoConfiguration:29-31`）：
   `resolve` 就是 `context.header("X-Tenant-Id").map(Tenants::of)`，全链路无任何环节把它与认证主体关联，
   且租户过滤器注册在 `HIGHEST_PRECEDENCE+15`（Spring Security 之前）。
   开启多租户 + 默认配置 = 能编译、能启动、任何调用方改一个 header 就能读写任意租户数据。
2. **exclude-path 可被路径穿越借用**（`TenantResolutionFilter:61`）：用 `request.getRequestURI()` 原始值做
   Ant 匹配。容器为选 handler 会把 `/actuator/../orders` 规约成 `/orders`，而 `getRequestURI()` 仍报穿越串 →
   命中默认的 `/actuator/**` 排除项 → **业务端点在 REJECT 策略下完全跳过租户解析**，再叠加上面的哨兵回退。
3. **进程内 relay 腿不绑租户**（`InProcessOutboxDispatcher:54-61`）：Kafka 消费桥用 `TenantContext.runAs` 包住
   整个事务并注明"必须在任何东西碰数据库之前绑定"，而进程内腿手里明明有 `message.tenantId()` 却完全不绑。
   同一个 handler 收同一个 envelope，走 Kafka 有租户、走本地没有。

## 根因（第一性）

1. **观察 vs 期望**：期望"多租户开启后，任何 tenant-scoped 读写要么用正确租户、要么失败"；
   实际"用正确租户、或静默用共享桶"。
2. **最小机制**：`TenantContext` 只提供 `current(): Optional` 与无条件抛出的 `require()`。
   前者把决策推给调用点，后者在单租户（N=1，哨兵合法）下不可用。于是**每个调用点都必须自己做一次决策**，
   而在每一个局部看，`orElse(ROOT)` 都是"对的"——因为单租户确实该落哨兵。
3. **真根因**：哨兵这个设计（单租户 = N=1，`tenant_id NOT NULL DEFAULT '__root__'`）本身是对的，
   但它让**"没绑定"与"单租户"两种语义共用了同一个返回值**。缺的不是各处的判断，而是**一个知道当前部署
   处于哪种模式的收口点**——模式是部署级事实，却从未被建模，所以 14 处只能各自假设最宽松的那种。
4. **排除的伪根因**：不是拦截器不可靠（`TenantLineInterceptorIntegrationTest` 证明它正确改写 SQL）；
   不是 `MissingTenantPolicy` 设计错（它对边缘是对的）；
   也不是"忘了在某处调用 policy"——policy 的形状（按请求决策）根本无法表达"基础设施层缺绑定"这件事。

**测试把缺陷固定成了预期**，这是根因还在的最强证据：
- `TenantContextTenantLineHandlerTest.tenantIdFallsBackToTheRootSentinelWhenNoneBound`
- `TenantLineInterceptorIntegrationTest.withNoTenantBoundTheRootSentinelIsUsedAndMatchesNothing`
  —— 测试名直接把"匹配不到任何行"写成了期望行为。

## 复现（test-first）

```java
// aipersimmon-ddd-tenancy-mybatis-plus，多租户开启、无绑定
TenantContext.setRequired(true);
handler.getTenantId();
// 修复前：返回 StringValue("__root__")，SQL 被改写成 WHERE tenant_id = '__root__'
// 修复后：抛 MissingTenantException
```

```java
// 端到端：三行分属 acme(2) / globex(1)，无绑定时查询
mapper.selectList(null);
// 修复前：返回 0 行（"数据消失"，调用方无从分辨）
// 修复后：根因异常为 MissingTenantException
```

```java
// 路径穿越借用排除项
when(request.getRequestURI()).thenReturn("/actuator/../orders");
filter.shouldNotFilter(request);
// 修复前：true（跳过租户解析）
// 修复后：false
```

## 修复

**一个收口点。** `TenantContext.effective()` 成为唯一决策处，14 处调用点全部改为调用它：

| 绑定 | 多租户 | `effective()` |
|---|---|---|
| 有 | 任意 | 该租户 |
| 无 | 关 | `Tenants.ROOT`（单租户 = N=1，每行本就带哨兵） |
| 无 | **开** | **抛 `MissingTenantException`** |

- 模式建模为部署级事实：`TenantEnforcement`（框架无关，放在 `aipersimmon-ddd-tenancy`）由两个 tenancy
  auto-config 以 `@Bean(initMethod="enable", destroyMethod="disable")` 绑定到上下文生命周期。
  两处都注册是因为 `tenancy-mybatis-plus` 不依赖 starter 却是真正改写 SQL 的模块，
  它的安全性不能取决于兄弟模块在不在；`@ConditionalOnMissingBean` 保证只建一个。
  destroy 会降回旗标，避免同 JVM 内先后两个上下文互相继承模式。
- 删掉语义与 `effective()` 重叠的 `require()`（同一概念两个名字，正是本仓库禁止的形态）。
- **异步传播**：新增 `TenantContextTaskDecorator`，在提交线程捕获租户、在工作线程 `runAs` 包住任务并复原。
  Spring Boot 只在恰好一个 `TaskDecorator` bean 时才应用它，所以消费方自带 decorator 时本 bean
  `@ConditionalOnMissingBean` 主动退出——否则会静默把对方的 decorator 也一起废掉。
  提交时无绑定则原样返回：凭空造一个租户正是本次要消灭的静默兜底，交给 `effective()` 响亮失败。
- **默认解析器改为显式 opt-in**：新增 `aipersimmon.ddd.tenancy.trust-header`（默认 `false`）。
  多租户开启 + 无自定义 `TenantResolver` + 未 opt-in → 抛 `UntrustedTenantHeaderException` 拒绝启动，
  并配 `UntrustedTenantHeaderFailureAnalyzer`（照 `MissingOperationLogResolverFailureAnalyzer` 先例，
  经 `META-INF/spring.factories` 注册）把两种安全接法直接写进启动错误里。
  没有可猜的安全默认——框架不知道你的 principal 形状——所以强制做决策。
- **exclude-path 改按容器派发路径匹配**：`dispatchPath()` 规约路径，并对 `;` 路径参数、
  `%2f`/`%5c`/`%2e` 编码、无法消解的前导 `../` 一律返回 `null`（= 不排除），
  宁可去解析并拒绝，也不跳过。
- **Kafka `ce_tenantid`**：多租户开启时它升级为必需 CloudEvents 属性，走与其他必需属性同一条
  `require()` 路径（永久失败 → 死信）；关闭时仍容忍缺失以兼容前租户时代的消息。
- **进程内 relay 腿**补上 `TenantContext.runAs(Tenants.fromValue(message.tenantId()), ...)`，与 Kafka 桥对齐；
  `outbox-spring-boot-starter` 相应显式声明 tenancy 依赖（此前靠 cqrs 传递）。

## 验证结果

- **全量 `mvn verify`（47 模块，含 Testcontainers PG/MySQL、spotless-check、PMD/CPD、SpotBugs）：BUILD SUCCESS。**
- 两个把缺陷固定为预期的测试已反转为断言 fail-closed（改了测试名，旧名字本身在描述 bug）。
- 新增覆盖：`TenantContextTest`（`effective()` 三种组合 + `TenantEnforcement` 升降旗）、
  `TenantContextTaskDecoratorTest`（跨线程携带 / 未绑定时响亮失败 / 池线程不残留绑定）、
  `AipersimmonDddTenancyAutoConfigurationTest`（默认拒绝启动、自定义解析器免 opt-in、
  上下文期间旗标为真且关闭后降回、decorator 让位于消费方的）、
  `TenantResolutionFilterTest.doesNotLetATraversalBorrowAnExcludedPrefix`（5 种穿越写法）。
- **与原方案的差异**：原计划把决策收口成 `Tenants.requireCurrentOr(...)`。实际放在 `TenantContext`
  而非 `Tenants`——`Tenants`（工厂/常量）与 `Tenancy`（策略）两个类名过于接近，会制造新的同名混淆；
  而 `current()` 就在 `TenantContext` 上，收口点与它并列最自然。
- **仍然开着的相邻问题**（本 issue 不含，见 `report-00003`）：幂等过滤器仍在 Spring Security 之前
  且键不含主体（C1/C2）——那是 web 模块的 SPI 重写，与本次的租户收口正交。
  `TenantResolutionFilter` 的拒绝仍走 `sendError`（HTML 错误页）而非 RFC 9457 problem+json，
  与框架其他过滤器不一致，留待 web 一并处理。

## 关联

- 父：[[report-00003-ddd-library-review-2026-07-29]]
- 决策：[[decision-00018-multi-tenancy-boundaries]]（命题 3 与 13 已据此修订）
- 设计：[[design-00009-multi-tenancy-tenant-id]]、规格：[[spec-00002-multi-tenancy]]
