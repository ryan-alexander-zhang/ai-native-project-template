---
id: issue-00050-outbox-relay-budget-and-config-validation
type: issue
role: main
status: resolved
parent: plan-00006-middleware-integration
---

# Outbox relay:出厂默认违反自身租约预算不变量 + 关键配置属性无 Bean Validation

## 问题(现状,file:line 为证)

- **等级:Low(运维健壮性;两处相关的配置卫生问题,合并处置)**。

**(a) 默认值违反"batch × send-timeout ≪ lock-at-most-for"这条它自己写下的不变量。**
- relay 单线程逐条**阻塞等 ack** 后才标 `sent`,单轮墙钟最坏 ≈ `batch-size × send-timeout`。
- 出厂默认:`batch-size = 100`(`AipersimmonDddOutboxJdbcAutoConfiguration.java:105`;mybatis 版同构)、
  Kafka `send-timeout = 30000ms`(`KafkaMessagingProperties.java:52`)、relay
  `lock-at-most-for = PT10M`(`OutboxRelay.java:138`)。
- 最坏单轮 = `100 × 30s = 3000s = 50min`,**远超** 10min 的 ShedLock 租约。
- 而 `KafkaMessagingProperties.java:49-50` 的 javadoc **自己明确要求**:"Keep `outbox.batch-size × this`
  comfortably below the relay's `relay.lock-at-most-for`"。**出厂默认恰恰不满足这条自述约束。**
- 后果:全批 stall 时锁在任务未结束前过期,另一实例拿锁并发 relay 同一批未标 `sent` 的行 → 在 broker 已不健康
  时翻倍压力(与 [[issue-00011-bound-outbox-kafka-send-await]] 描述的跨实例并发投递同源)。
- **来历**:issue-00011(已 resolved)修的是"单条 ack **无界**等待",并在其「边界」一节把本条(批级预算 vs 租约)
  **显式记为"属运维调参边界,非本 issue 主修点"而 defer**。此处把它单列,让"defer"变成可追踪的待办。

**(b) 关键配置属性无 Bean Validation:负/零值无启动拦截。**
- outbox-jdbc 的 `batch-size`/`max-attempts`/`base-backoff-ms`/`retention-seconds` 都是**裸 `@Value` int/long**
  (`AipersimmonDddOutboxJdbcAutoConfiguration.java:105-107,129`),无 `@Min/@Positive`。
- 全 `aipersimmon-ddd-outbox*` 与 `aipersimmon-ddd-messaging-kafka` 的 main 源码里,除
  `KafkaMessagingProperties` 挂了 `@ConfigurationProperties` 外,**无任何** `@Validated/@Min/@Max/@Positive/@NotNull`
  (`jakarta.validation`)。
- 后果(误配即坏,且无启动期反馈):
  - `batch-size <= 0` → `SELECT ... LIMIT 0`/负 → relay 每轮取 0 行,**永不推进**,消息滞留而无报错;
  - `max-attempts <= 0` → 首次失败即达上限 → **立即死信**(健康消息被误判耗尽);
  - `send-timeout <= 0` → 每条 ack **立即超时** → 全部转瞬时失败、无限重试空转;
  - `retention-seconds < 0` → cleanup cutoff 落在未来 → **删除尚在有效期(甚至未发)的行**。

## 根因(第一性)

1. **(a) 可靠性语义把线程变成稀缺资源,但预算只写在注释里、没写进默认值。** "阻塞等 ack 才标 sent"要求这条阻塞
   **有界且总预算 < 租约**;issue-00011 给了单条上界,却把"批级总预算"留给运维,默认值本身没兑现注释里的约束。
2. **(b) `@Value` 直取绕过了校验层。** 属性没有集中到一个 `@Validated @ConfigurationProperties`,于是没有天然的
   约束落点;非法值一路穿到运行期,表现为"静默空转/误死信/误清理"而非启动失败——与 fail-fast 的装配原则相悖
   (对照 [[issue-00021-starter-config-fail-fast-and-wiring]] 已确立的方向)。

## 复现(test-first)

- (a) 装配测试:令 `batch-size × send-timeout > lock-at-most-for`(甚至用出厂默认),断言启动期**跨字段校验**报错
  (修复后);或文档化默认值使三者出厂即满足不变量。
- (b) 参数化装配测试:分别注入 `batch-size=0`、`max-attempts=0`、`send-timeout=0`、`retention-seconds=-1`,断言
  **启动失败**并给出可读消息(修复后);现状为启动通过、运行期出现空转/误死信/误删。

## 修复/建议(minor)

- **(b) 优先、最省**:把 outbox 的这几个属性收敛到一个 `@Validated @ConfigurationProperties` 类
  (`aipersimmon.ddd.outbox.*`),加 `@Min(1)`(batch-size、max-attempts、send-timeout)、`@PositiveOrZero`
  (retention-seconds、base-backoff-ms),让非法值**启动即失败**。与 issue-00021 的 fail-fast 一致。
- **(a) 跨字段校验 + 修默认**:在装配期做一次 `batch-size × send-timeout <= lock-at-most-for` 的跨字段断言(不满足
  则启动失败或响亮 WARN);并复核出厂默认使其**默认即满足**(例如下调 `batch-size`,或对齐三者)。
- **(a) 长期(超出本 issue)**:相较"单个全局定时任务锁保护整批",行级 claim/lease/fencing 更能根治批级预算与租约的
  张力;记为后续方向,非本 issue 必须。

**注意改动面**:(b) 引入 `spring-boot-starter-validation`(或 `jakarta.validation` + 校验实现)并把 `@Value` 迁到
`@ConfigurationProperties`;(a) 增加一个装配期跨字段校验点并可能调整默认值。不改 schema、不改 `OutboxDispatcher`
/`Inbox` 接口。两者均需补装配测试。

## 已修复

- **(b) 无依赖显式校验**:新增 `OutboxProperties`(`@ConfigurationProperties("aipersimmon.ddd.outbox")` +
  `InitializingBean`),把散落的 `batch-size`/`max-attempts`/`retry.*`/`cleanup.retention-seconds` 收敛为一处,
  `afterPropertiesSet()` 显式 `if-throw` 校验(不引入 `jakarta.validation`):`batch-size>=1`、`max-attempts>=1`、
  `base-backoff-ms>=0`、`max-backoff-ms>=base-backoff-ms`、`retention-seconds>=0`,非法值启动即失败。经
  `@EnableConfigurationProperties` 挂到共享 `AipersimmonDddOutboxAutoConfiguration`;jdbc / mybatis 两个装配的 relay
  与 cleanup 由裸 `@Value` 改为注入该 POJO。测试:`OutboxPropertiesTest`(默认合法 + 五类非法值各自抛)。
- **(a) 默认满足不变量 + 越界告警**:relay `lock-at-most-for` 默认 `PT10M`→`PT60M`(4 处:jdbc/mybatis 各自的
  `@SchedulerLock` 与 `@EnableSchedulerLock`;`cleanup.lock-at-most-for` 不变),使出厂 `100×30s=50min < 60min` 成立。
  另在 `messaging-kafka` 加一个启动期 `SmartInitializingSingleton`(`aipersimmonDddOutboxLeaseBudgetCheck`,与
  durable guard 同门:`@ConditionalOnBean(KafkaTemplate)` + `OnExternalizedEventsCondition`):当自定义配置使
  `batch-size × send-timeout > relay.lock-at-most-for` 时打响亮 WARN(非 fail——最坏情形需持续 broker 宕机,运维可
  知情接受)。`OutboxProperties` 经 `ObjectProvider` 可选,纯消费端应用无 outbox 时跳过。

> 长期方向(超出本 issue,未做):行级 claim/lease/fencing 取代"单定时锁保护整批",从根上消解批级预算与租约的张力。

> 验证:全 reactor `clean verify`(Java 21)`BUILD SUCCESS`——PMD/CPD、SpotBugs、Spotless、ArchUnit、全部测试
> (含新 `OutboxPropertiesTest`、既有 `AutoConfigurationWiringTest`,以及 `messaging-kafka` 的嵌入式 Kafka 集成测试)
> 均通过。(先前一次本地跑不完是因为 Maven 误跑在 Homebrew JDK 26 上,SpotBugs/PMD 无法解析 major version 70 字节码;
> 统一到 Java 21 后门禁恢复正常,与本次改动无关。)

## 关联

- [[plan-00006-middleware-integration]]
- [[issue-00011-bound-outbox-kafka-send-await]]((a) 是其「边界」一节显式 defer 的批级预算项)
- [[issue-00021-starter-config-fail-fast-and-wiring]]((b) 的 fail-fast 装配原则先例)
