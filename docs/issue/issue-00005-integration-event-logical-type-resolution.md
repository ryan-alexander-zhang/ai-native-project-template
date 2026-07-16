---
id: issue-00005-integration-event-logical-type-resolution
type: issue
role: main
status: resolved
parent: decision-00014-cloudevents-integration-event-contract
---

# 覆盖 `eventType()` 的集成事件无法被默认 resolver 消费(逻辑类型注册表按简单类名建键)

[[decision-00014-cloudevents-integration-event-contract]] 命题一确立"事件类型是**逻辑契约**,不是 Java 类",
并推荐把 `eventType()` 覆盖成版本化、命名空间化的名字(如 `com.example.ordering.OrderPlaced.v1`)。但默认
类型注册表**按 `Class.getSimpleName()` 建键**,与生产侧写到线上的 `eventType()` 不一致——**一旦按推荐做法覆盖
`eventType()`,producer 能发,默认 in-process / Kafka consumer 必然解析失败**。同时 `putIfAbsent` 会静默吞掉
同名冲突,可能把消息反序列化成**错误的类**。这直接击穿 decision-00014 的 published-language 解耦目标。

## 问题(现状)

- **等级:High**。失败点恰在决策**推荐的用法**上:越是遵循"版本化/命名空间逻辑类型"的正确实践,越必然踩中。
- 覆盖 `eventType()` → 默认 consumer 抛 `IllegalStateException: no integration event type registered for
  'com.example.ordering.OrderPlaced.v1'`。
- 两个不同 BC 里出现同名 `OrderCreated`(经共享 contracts 消费)→ 先扫到的赢,后者被静默丢弃,`resolve` 可能
  返回错误的类(比抛异常更隐蔽)。

## 根因(第一性)

1. **期望**:producer 写到信封 `type` 的值,与 consumer 注册表查表用的键,必须是**同一个值**——即事件的逻辑
   类型。**实际**:producer 写 `eventType()`,consumer 注册表键为 `getSimpleName()`,二者仅在"未覆盖
   `eventType()`"时偶然相等。
2. **最小机制(file:line)**:
   - 生产侧把逻辑类型写上线:`aipersimmon-ddd-outbox-jdbc/.../OutboxWriter.java:49`(`event.eventType()`;
     mybatis-plus 版与 `SpringIntegrationEvents` 同构)。
   - 消费侧注册表**改按简单类名建键**(改造前):`AipersimmonDddOutboxAutoConfiguration` 扫描时
     `byType.putIfAbsent(c.getSimpleName(), ...)`。
   - 解析:`RegistryIntegrationEventTypeResolver.resolve(type)` 先查表(键=simpleName)必然 miss,再
     `Class.forName(type)` fallback;而 `com.example.ordering.OrderPlaced.v1` 不是可加载的类 →
     `ClassNotFoundException` → 抛 `IllegalStateException`。
3. **真正根因**:注册表的键**不是从"逻辑类型"这一契约身份派生的**,而是取了一个"默认实现下恰好相等"的替身
   (simple name)。加之 `putIfAbsent` 把同名冲突当作幂等而非错误。**不是** `Class.forName` fallback 的问题(它
   只是兜底),**不是** 序列化的问题——payload 序列化/反序列化本身正确。
4. **为何当初退化成 simple name**:`eventType()` 是**实例方法**,扫描器只有 `Class` 对象、无法在不实例化
   record 的前提下调用它——所以取了 `getSimpleName()` 的捷径。**修复的关键因此不是"改成按 `eventType()` 建键"**
   (做不到),而是**先让逻辑类型可从类静态读取**。

## 复现(测试先行)

- `aipersimmon-ddd-outbox-jdbc` · `OutboxJdbcTest#annotatedLogicalTypeIsStampedOnTheWireAndResolvesBackToTheLocalClass`:
  发布一个逻辑类型为 `com.example.ordering.OrderPlaced.v1` 的事件,断言(a)outbox 行 `type` = 该逻辑类型,
  (b)**自动装配的** `IntegrationEventTypeResolver` 能把它解析回本地类。修复前 (b) 抛 `IllegalStateException`。
- `aipersimmon-ddd-outbox` · `OutboxTypeRegistryTest`:两个类声明同一逻辑类型时 `register` 抛错(修复前
  `putIfAbsent` 静默丢弃);未注解事件 `register` 抛错。
- `aipersimmon-ddd-integration` · `IntegrationEventTest#failsWhenNotAnnotated` / `failsWhenAnnotationValueIsBlank`:
  无 / 空 `@EventType` 时 `eventTypeOf` 与 `eventType()` 抛 `IllegalStateException`。

## 修复

逻辑类型**必须显式声明**,不得从类名推导——类名是实现细节,不是发布契约。用注解让 producer 写线的值与
consumer 建键的值同源,**缺失即硬报错**(不回退简单类名):

1. **`@EventType(name, version)` 注解**(`aipersimmon-ddd-integration`,framework-free,`@Retention(RUNTIME)` /
   `@Target(TYPE)`):`name`(逻辑类型 = 线上 `type` = 注册表键,稳定标识)+ `version`(schema 修订 =
   CloudEvents `dataschemaversion`),二者**都必填**,合成 `(name, version)` 精确解析键。语义:payload schema 变化
   bump `version`、业务事实语义变化换 `name`;**每个 `(name, version)` 是独立契约、各自一个本地类**(见下方§边界收口
   #3 按 `(type, version)` 建键——消费者保留旧版本类或经自定义 catalog 映射才能继续消费/重放旧版本,无隐式回退)。
   版本作为显式结构化字段,也给后续 lint(改了 schema 却没 bump 版本)留了钩子。
2. **`IntegrationEvent` 读注解 + 抛错**(`IntegrationEvent.java`):`eventTypeOf` 读 `name`(无注解 / 空即抛)、
   `eventVersionOf` 读 `version`(无注解 / `<1` 即抛);`default eventType()` / `eventVersion()` 委托它们。**不回退
   简单类名**。用注解而非覆盖方法:方法覆盖只在运行期实例上可见,类扫描注册表看不到。
3. **版本落到信封(修掉既有缺陷)**:三个发布器(`OutboxWriter` jdbc/mybatis、`SpringIntegrationEvents`)此前把
   `EventEnvelope.version` **硬编码为 1**,与事件无关;改为 `event.eventVersion()`。于是 `dataschemaversion`
   (Kafka `ce_dataschemaversion` 头 / outbox 行 `version` 列)首次真正反映事件声明的版本。
4. **注册表按 `name` 建键 + 重复即失败**(`AipersimmonDddOutboxAutoConfiguration.register`):缺注解的被扫描事件
   启动即抛错;同一类重复扫描幂等,**两个类声明同一 `name` 则启动即抛错**,取代静默 `putIfAbsent`。
5. **构建期护栏(ArchUnit)**:`AiPersimmonDddRules.integrationEventsShouldDeclareEventType()` 强化为自定义
   `ArchCondition`——凡 `implements IntegrationEvent` 必须有 `@EventType`、`name` 非空、`version ≥ 1`、且
   `name` 全局唯一;并入 `all()`,在 Spring 启动前、构建期即拦截(承
   [[issue-00002-land-domain-event-handler-annotation]] / [[issue-00004-enforce-no-command-handler-to-command-handler-dependency]]
   同一范式)。**"代码改了但版本没 bump" 的漂移 lint 需要签入结构快照 + CI 比对,非 ArchUnit 能力所及,记为后续**。

## 边界收口(单一事实来源 + (type, version) catalog)

在上述基础上进一步守住 6 条边界:

1. **删除 `eventType()` / `eventVersion()` 实例方法**:只保留静态 `IntegrationEvent.eventTypeOf/eventVersionOf`
   读注解。方法可被覆盖 → 双事实来源,删掉即消除;发布器改调静态读法。
2. **type 无 simpleName 默认**:已守(缺注解即抛)。
3. **重复 `(type, version)` 启动失败**:注册表键从 `name` 改为 `(name, version)`,`register` 对同一 pair 的两个
   类 fail-fast(取代 `putIfAbsent`);同名不同版本允许共存(修订上一轮"版本=元数据、按 name 解析"为"按
   `(type, version)` 解析")。
4. **缺注解事件发布即失败**:已守(`eventTypeOf`/`eventVersionOf` 在发布路径抛)。
5. **未知 `(type, version)` 入站 → DLT,无 FQCN 回退**:删除 `RegistryIntegrationEventCatalog` 的 `Class.forName`
   回退;lookup miss 抛 `UnknownIntegrationEventException`(永久失败语义)。**注意**:真正的"路由到 DLT"依赖
   [[issue-00003-messaging-delivery-reliability]](Kafka 侧尚无 `DeadLetterPublishingRecoverer`);当前是"清晰
   抛出→由容器处理",DLT 落地随 issue-00003。outbox 侧未知类型经 relay 重试达上限后进 outbox 死信(已有)。
6. **可覆盖的 `IntegrationEventCatalog` SPI**:`IntegrationEventTypeResolver` 重命名为 `IntegrationEventCatalog`
   (`Optional<Class> lookup(String type, int version)`),默认实现 `RegistryIntegrationEventCatalog` 由注解扫描
   构建(正常路径);应用可覆盖该 bean 做动态注册 / 第三方事件 / 历史版本迁移——非默认路径。

## 验证结果

- **新增/改动测试全绿**:`IntegrationEventTest`(4,读 name+version / 未注解 / 空 name / 版本非正 各自报错)、
  `OutboxTypeRegistryTest`(5,按 `(name, version)` 建键 / 同名异版共存 / 同类幂等 / 未注解报错 / 同 pair 冲突
  fail-fast)、`InProcessOutboxDispatcherTest`(3,catalog 回环 / 未知 type / 未知 version 均抛
  `UnknownIntegrationEventException`)、`OutboxJdbcTest`(4,`catalog.lookup(type, version)` 端到端回环)、
  `KafkaIntegrationEventListenerTest`(2,catalog 按 (type,version) 解析)、`AiPersimmonDddRulesTest`(47)。既有断言
  从"默认简单类名"改为"声明的 `@EventType` name"。
- **库全反应堆 `mvn test` BUILD SUCCESS** + **multi-module 脚手架 `mvn test` 绿**(`OrderingFlowTest` 跨上下文 saga
  经带版本的事件回环;`ArchitectureTest` 经 `all()` 含新规则)。约 12 个既有集成事件(库测试夹具 + 三脚手架契约)
  迁移为 `@EventType(name, version=1)`;仓库内无一覆盖过 `eventType()` 方法,故无方法迁移。
- 范围限定为**库 + multi-module**;modulith / microservice 脚手架不在本次验收范围,但已 `mvn test-compile` 确认
  注解 API 变更(`value()` → `name()`+`version()`)未使其失编译。microservice e2e 另有与本 issue 无关的既有 schema
  缺口(缺 `shedlock` 表、inbox 缺 `consumer` 列,源自 outbox 加固提交 `4a0e94b`),未处理。

## 影响 / 行为变化(需知会)

- **强 breaking**:任何 `IntegrationEvent` 未标 `@EventType`,`eventType()` / 扫描建表即**抛错**(发布或启动时)。
  这是刻意的——逻辑类型是必须显式声明的发布契约。ArchUnit 规则在构建期先行拦截。
- 同名冲突亦 fail-fast:两个类声明同一 `@EventType` 值启动即失败(此前 `putIfAbsent` 静默择一)。修复过程中本仓库
  两个测试类各自的嵌套 `record SampleEvent` 同名同包即因此暴露,已改名 `InProcessSampleEvent` 消歧——反证了
  "静默择错类"隐患真实存在。

## 关联

- [[decision-00014-cloudevents-integration-event-contract]] —— 逻辑类型契约的来源;本 issue **修正**其"`eventType()`
  默认简单类名、按简单类名零配置建表"的做法为"`@EventType` 必填、无回退、缺失即报错"(decision 正文已同步修订)。
- [[issue-00002-land-domain-event-handler-annotation]]、[[issue-00004-enforce-no-command-handler-to-command-handler-dependency]]
  —— 同一"契约用注解声明 + ArchUnit 固化"范式。
- [[issue-00003-messaging-delivery-reliability]] —— 反序列化永久失败进死信,与本 issue 的"解析失败"相邻但正交。
