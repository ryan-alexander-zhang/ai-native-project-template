---
id: issue-00044-integration-events-bypass-outbox-kafka-at-runtime
type: issue
role: main
status: resolved
parent: design-00006-integration-event-routing
---

# 运行期集成事件被进程内发布器抢占,出站箱→Kafka→入站箱可靠传输整体被旁路

## 问题(现状,file:line 为证)

- **等级:框架真实缺陷**(库侧 bean 装配条件不周,非样例演示问题)。影响任何同时依赖
  `aipersimmon-ddd-events-spring` + `aipersimmon-ddd-outbox-mybatis-plus` 且未引入 `-outbox-jdbc` 的工程——
  多模块脚手架正是这种组合。
- 现象:`multi-module` 样例真实启动(docker-compose PG+Kafka)下,尽管事件均 `@Externalized`、路由已装配
  (启动日志 `integration event externalization routes -> topics [inventory.events, ordering.events, payment.events]`),
  可靠传输链路**全程零活动**,业务流程却仍正确完成:
  - `aipersimmon_outbox` 行数恒为 **0**(把 `aipersimmon.ddd.outbox.poll-delay-ms` 调到 30000 让行"驻留"仍抓不到任何行);
  - Kafka 从未创建 Producer(日志 `Producer clientId` 计数 = 0),三主题末端偏移量均为 **0**;
  - `aipersimmon_inbox` 行数为 **0**;
  - 出站箱中继线程正常轮询但 `OutboxMapper.selectDue` 恒 `Total: 0`,DEBUG 全程无任何 `OutboxWriter` INSERT。
- `IntegrationEvents` 端口三实现,均带 `@ConditionalOnMissingBean(IntegrationEvents.class)`:
  - 进程内 `SpringIntegrationEvents`(`aipersimmon-ddd-events-spring/.../AipersimmonDddEventsAutoConfiguration.java:32-38`);
  - `OutboxWriter`(`aipersimmon-ddd-outbox-jdbc/.../OutboxWriter.java`);
  - `OutboxWriter`(`aipersimmon-ddd-outbox-mybatis-plus/.../OutboxWriter.java:31`,类名
    `com.aipersimmon.ddd.outbox.mybatisplus.OutboxWriter`,由 `AipersimmonDddOutboxMybatisPlusAutoConfiguration.java:88-90`
    以 `@ConditionalOnBean(SqlSessionFactory)` + `@ConditionalOnMissingBean(IntegrationEvents)` 提供)。
- `events-spring` 的守卫只排除 **jdbc 版** `OutboxWriter`:
  ```java
  // AipersimmonDddEventsAutoConfiguration.java:32-38
  @Bean
  @ConditionalOnMissingBean(IntegrationEvents.class)
  @ConditionalOnMissingClass("com.aipersimmon.ddd.outbox.jdbc.OutboxWriter")   // ← 只认 jdbc 版
  public IntegrationEvents integrationEvents(...) { return new SpringIntegrationEvents(...); }
  ```
- 样例依赖为 `events-spring` + `outbox-mybatis-plus`(`mvn -pl start dependency:tree` 确认无 `-outbox-jdbc`),
  故 `com.aipersimmon.ddd.outbox.jdbc.OutboxWriter` 不在类路径 → 该 `@ConditionalOnMissingClass` 成立 →
  `SpringIntegrationEvents` 仍合格。

## 根因(第一性)

1. **观察 vs 期望**:期望"`@Externalized` 事件经出站箱持久化 → Kafka 至少一次投递 → 入站箱去重后进程内重放"
   (decision-00006 方式三 / design-00006 逐事件路由);实际"集成事件退化为进程内同步投递,出站箱/Kafka/入站箱三件套虽全装配却从不触发"。
2. **最小机制**:`SpringIntegrationEvents` 与 mybatis-plus `OutboxWriter` 都以
   `@ConditionalOnMissingBean(IntegrationEvents.class)` 竞争同一个 bean;`events-spring`
   自动配置无 ordering 约束,而 `outbox-mybatis-plus` 为 `@AutoConfiguration(after=...)`,故 `events-spring` 先注册、
   凭 MissingBean 抢占,mybatis-plus 的 `OutboxWriter` 退让。
3. **真根因**:`events-spring` 判定"出站箱是否在场"的守卫用了**具体实现类名**
   `@ConditionalOnMissingClass("...outbox.jdbc.OutboxWriter")`,只覆盖 jdbc 一种持久化,**漏掉 mybatis-plus 版**。
   守卫本意(见其 Javadoc:"Provided only when the outbox starter is absent")未能覆盖 mybatis-plus 出站箱,
   于是进程内发布器在本应由出站箱接管时仍然胜出。
4. 影响面:单体内业务流程因流程管理器直接消费进程内事件仍能跑通,**但可靠跨进程传输能力形同虚设**——
   一旦上下文拆成独立部署,或依赖至少一次投递 + 入站箱去重,当前运行期行为不满足设计。

## 复现

```bash
cd aipersimmon-ddd-scaffold/multi-module/start
docker compose -f compose.yaml up -d db kafka
mvn spring-boot:run -Dspring-boot.run.arguments="--spring.docker.compose.enabled=false \
  --spring.datasource.url=jdbc:postgresql://localhost:5432/ordering \
  --spring.datasource.username=postgres --spring.datasource.password=postgres \
  --spring.kafka.bootstrap-servers=localhost:9092 --otel.sdk.disabled=true \
  --aipersimmon.ddd.outbox.poll-delay-ms=30000"
# 下单后:
docker exec start-db-1 psql -U postgres -d ordering -tAc "SELECT count(*) FROM aipersimmon_outbox"   # → 0
docker exec start-db-1 psql -U postgres -d ordering -tAc "SELECT count(*) FROM aipersimmon_inbox"    # → 0
docker exec start-kafka-1 bash -c "/opt/bitnami/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 --topic ordering.events"  # → :0:0
# 而订单仍到达 CONFIRMED / CANCELLED,流程实例 COMPLETED —— 全程进程内投递
```

## 为何现有测试未发现(测试盲点)

- `OutboxAtomicityTest.java:53-55` 仅断言回滚后 `outbox == 0`;当事件**从不写出站箱**时该断言天然成立(0==0),
  属假阴性——应先断言"提交前出站箱存在该行"。
- `OrderingFlowTest` / `PaymentCompensationFlowTest` 只断言业务终态,进程内投递即可满足;其 Javadoc 描述了
  "出站箱→Kafka→入站箱"链路,但**无任何断言校验消息真的过了 Kafka、或入站箱去重生效**。

## 决定方案(方案 3 · 全量 · 施工中)

第一性原理:**传输方式由依赖决定;声明的最强传输胜出;进程内仅在无 outbox 时兜底;使用方既不手工接线,也不该遭遇静默降级。**
现设计的意图是对的,错在**机制**(基础层用硬编码类名去认下游实现 + 无序竞态 + 静默失败)。分三层修:

### (a) 让"有 outbox ⇒ outbox 胜"确定化,且基础层不认识具体实现类

- **删除** `events-spring` 里 `SpringIntegrationEvents` 的 `@ConditionalOnMissingClass("...outbox.jdbc.OutboxWriter")`,只保留 `@ConditionalOnMissingBean(IntegrationEvents.class)`。
- **由传输侧宣告优先级**:两个 outbox writer autoconfig(`AipersimmonDddOutboxJdbcAutoConfiguration`、
  `AipersimmonDddOutboxMybatisPlusAutoConfiguration`)各加
  `@AutoConfiguration(beforeName = "com.aipersimmon.ddd.events.spring.AipersimmonDddEventsAutoConfiguration")`
  (字符串,无编译依赖;events 不在时安全忽略)。于是 outbox 在场时其 `OutboxWriter` 先注册,events 的
  `@ConditionalOnMissingBean` 自然让位。新增任何 outbox 口味只需带同一注解,`events-spring` 永远不必回改——
  知识方向由"基础层认识下游实现"**反转**为"传输模块声明自己先于进程内兜底"。

### (b) 生效传输可见 + 矛盾配置 fail-loud(对使用方最关键)

- **可见**:三处 `@Bean` 落点各打一行 INFO —— outbox writer 注册时 "integration events transport = transactional outbox (jdbc/mybatis-plus)";
  events 兜底注册时 "= in-process (local)"。使用方开机即知走哪条路。
- **fail-loud**:在 outbox-core 引入标记接口 `OutboxIntegrationEvents extends IntegrationEvents`,两个 `OutboxWriter` 实现它;
  `messaging-kafka` 增一个启动校验 bean(以 `OnExternalizedEventsCondition` 限定"确有 `@Externalized` 事件"时才装配):
  若生效的 `IntegrationEvents` **不是** `OutboxIntegrationEvents`(即进程内在当值、事件到不了 broker)→ **启动即抛**,
  信息明确("有 @Externalized 事件与 Kafka,但集成事件走进程内、永远到不了 Kafka;请加 outbox 模块或改回 LOCAL")。
  这一层把"只在生产暴露"的静默 footgun 变成开机报错;它也能回归防住本 bug 本身。

### (c) 堵测试盲点

- `start` 新增集成测试:下单后断言 `aipersimmon_outbox` 出现行、Kafka 主题偏移量 > 0、`aipersimmon_inbox` 出现去重行,
  证明真的走了 outbox→Kafka→inbox,而非只看订单终态。
- 修正 `OutboxAtomicityTest`:回滚断言前**先断言提交前 outbox 有该行**,消除 0==0 假阴性。

> 范围:改动跨 `events-spring` / `application`(marker) / `outbox-jdbc` / `outbox-mybatis-plus` / `messaging-kafka` 五个库模块,
> 加样例 `start` 的测试。不新增强制配置项——依赖仍是唯一真相源,`@ConditionalOnMissingBean` 既有口子保留为覆盖逃生舱。

## 修复(已实施)

> marker 最终落在 `aipersimmon-ddd-application`(与 `IntegrationEvents` 同模块),命名按能力而非机制:
> `DurableIntegrationEvents`——"投递前已持久化、可被 relay 转发到 broker"。两个 `OutboxWriter` 实现它,进程内 `SpringIntegrationEvents` 不实现。

**(a) 确定化接线**
- `events-spring`:删除 `@ConditionalOnMissingClass("...outbox.jdbc.OutboxWriter")`,`integrationEvents` 仅保留 `@ConditionalOnMissingBean(IntegrationEvents.class)`。
- `outbox-jdbc` / `outbox-mybatis-plus` 的 `@AutoConfiguration` 各加
  `beforeName = "com.aipersimmon.ddd.events.spring.AipersimmonDddEventsAutoConfiguration"`,让 outbox writer 先注册、进程内兜底自然让位。基础层不再认识任何具体 outbox 类。

**(b) 可见 + fail-loud**
- 三处 `@Bean` 落点打 INFO:outbox 注册 → "durable transactional outbox (JdbcTemplate/MyBatis-Plus)";events 兜底 → "in-process (local)"。
- `application` 新增标记接口 `DurableIntegrationEvents extends IntegrationEvents`,两 writer 实现之。
- `messaging-kafka` 增启动校验 bean `aipersimmonDddDurableTransportGuard`(`SmartInitializingSingleton`,门控 `@ConditionalOnBean(KafkaTemplate)` + `@Conditional(OnExternalizedEventsCondition)`):当**存在**一个非 durable 的 `IntegrationEvents` bean 时启动即抛并给出补救指引;无 `IntegrationEvents` bean 的切片测试不受影响。

**(c) 堵盲点**
- 样例新增 `IntegrationEventTransportTest`:断言活跃 `IntegrationEvents` 是 `DurableIntegrationEvents`,且订单 CONFIRMED 后 `aipersimmon_inbox` 有行(证明真过了 Kafka)。
- `OutboxAtomicityTest`:回滚断言前先断言活跃 `IntegrationEvents` 是 `DurableIntegrationEvents`,消除 0==0 假阴性。

## 验证结果

- 库侧受影响 6 模块 `mvn install` 全绿(events-spring 5、outbox-jdbc 20、outbox-mybatis-plus 13+4、messaging-kafka 30、outbox-core 114…);guard 未破坏 messaging-kafka 既有装配/路由测试。
- 样例全反应堆 `mvn test`:**BUILD SUCCESS**;`IntegrationEventTransportTest` 1 pass、`OutboxAtomicityTest` 1 pass(含新前置断言)、其余(OrderingFlow/PaymentCompensation/ExceptionContract/Architecture…)全绿。
- 真实启动实测(docker-compose PG+Kafka)—— 与 F-1 原始"全 0"证据镜像对照:

  | 信号 | 修复前 | 修复后 |
  | --- | --- | --- |
  | 启动传输日志 | 无 | `durable transactional outbox (MyBatis-Plus)` |
  | `aipersimmon_outbox`(sent) | 0 | 4 |
  | `aipersimmon_inbox` | 0 | 4 |
  | Kafka 偏移量 ordering/inventory/payment | 0 / 0 / 0 | 2 / 1 / 1 |

  下单 → CONFIRMED,4 条集成事件确实走了 outbox→Kafka→inbox。

## 关联

- [[design-00006-integration-event-routing]](本条为其运行期未落地)
- [[decision-00006-integration-event-transport-selection]](方式三:出站箱→Kafka→入站箱)
- [[events-spring-shadows-mybatis-outbox]](记忆:同一装配陷阱)
- [[record-00001-multi-module-ddd-integration-verification]](F-1 完整证据)
- [[samples-not-reference]]
