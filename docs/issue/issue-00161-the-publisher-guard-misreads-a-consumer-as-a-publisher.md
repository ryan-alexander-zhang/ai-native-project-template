---
id: issue-00161-the-publisher-guard-misreads-a-consumer-as-a-publisher
type: issue
role: main
status: open
---

# 发布侧的启动检查把只消费的服务判成发布方（P2，误报）

2026-08-03 写 S4 的 samples 时撞到（`aipersimmon-ddd-samples/s04-integration-events-across-services/inventory-service`）。
**不是正确性缺陷**——它在启动期大声失败、信息清楚。是一个**合法部署形态上的误报**。

## 现象

一个只消费、从不发布的服务无法启动，除非加上一个它永远不写的 outbox 模块。

链条三步，全部可查：

1. **消费方必须带 `@Externalized`。** 消费桥及其错误处理器都以
   `OnExternalizedEventsCondition` 为门禁——
   `aipersimmon-ddd-messaging-kafka/src/main/java/com/aipersimmon/ddd/messaging/kafka/AipersimmonDddMessagingKafkaAutoConfiguration.java:190,260,302`。
   该条件本体只问一件事：
   `aipersimmon-ddd-outbox-spring-boot-starter/src/main/java/com/aipersimmon/ddd/outbox/spring/OnExternalizedEventsCondition.java:30`

   ```java
   .anyMatch(type -> IntegrationEvent.externalizedTarget(type).isPresent());
   ```

   它自己的 javadoc 也写明了理由：*"with zero externalized events there is no topic to subscribe to"*。
   所以消费方给自己那份契约拷贝打 `@Externalized("topic")` 是**订阅声明**，不是发布路由。

2. **同一个条件又被发布侧的 guard 当门禁。** 同文件 `:149`：

   ```java
   @Conditional(OnExternalizedEventsCondition.class)
   public SmartInitializingSingleton aipersimmonDddDurableTransportGuard(...)
   ```

   `:154` 检查发布器是否 durable，不是就抛：

   ```java
   if (active != null && !(active instanceof DurableIntegrationEvents)) {
   ```

3. **只消费的服务必然三条全中**：有 `@Externalized`（第 1 步逼出来的）、有 `KafkaTemplate`（Kafka
   starter 带的）、发布器是进程内那个非 durable 的（没装 outbox 时的默认）。于是启动失败，而
   `:160-161` 给出的唯一补救是 *"Add a durable outbox module (e.g.
   aipersimmon-ddd-outbox-mybatis-plus or aipersimmon-ddd-outbox-jdbc)"*。

**根本原因一句话**：一个只该管发布侧的启动检查，用了一个消费侧也必须满足的条件当门禁。

## 代价

sample 里的实际处置（`inventory-service/pom.xml` 与 `application.yaml` 有注释说明）：加
`aipersimmon-ddd-outbox-mybatis-plus`、把 `outbox` 加进 `aipersimmon.ddd.flyway.components`（否则
outbox 的 schema 校验器拒绝启动）、把 `outbox.relay.enabled` 关掉（否则每秒轮询一张永远空的表）。
结果是 `aipersimmon_outbox`、`aipersimmon_dead_letter`、`shedlock` 三张表建在一个从不发布的服务里，
永远为空。

消费与 outbox 运行期零交互——消费要的是 inbox，库本身也是分成两个独立依赖的。那三张表只是为了通过一个
启动检查而存在。

**教学上的代价更大**：照抄这个 sample 的团队会以为"消费方也要 outbox"，而这恰好是本框架分得最清楚的
两件事之一。

## guard 本身没错，也不能简单跳过

它防的事故是真的：一个 `@Externalized` 事件走进程内发布器，会在本地投递完然后**无声地永远不出 JVM**
——不报错、无死信、无消费延迟可告警。对发布方，启动失败是唯一正确的反应。

也**不能**简单地"`consumer.enabled=true` 就跳过"：存在既消费又发布、且没装 outbox 的应用，那正是 guard
要抓的真 bug，一律跳过就把它放过去了。

框架能知道"我是不是消费方"（`:299-301` 的 `consumer.enabled`，默认 false），但**推不出"我是不是发布
方"**——唯一的静态证据就是 `@Externalized` 的存在，而它已经被订阅复用了。启动期扫不出
`IntegrationEvents.publish` 的调用点。

旁证：同文件紧随 guard 之后的那段 javadoc 自述，租约 WARN 之所以把 `OutboxProperties` 做成可选，是为了
让 *"a Kafka-consumer-only app without an outbox is left alone"*——这个形态被设想过，只是 guard 这条
路径没照顾到。

## 修复要求

**(A) 推荐——加一个 messaging 模块自己拥有的 opt-out 属性。** 二义天生存在，所以让应用说一句，跟
`consumer.enabled` 当年替"我要不要消费"说一句是同一种做法。要点：

- 属性必须属于 `aipersimmon.ddd.messaging.kafka.*`，**不能**复用 outbox 的
  `allow-unreachable-external-events`——那个属性所在的模块正是缺席的那个；
- 默认值保持"检查开启"，把 opt-out 写成显式动作（例如 `publishes-externalized-events: false`），
  这样发布方忘了配不会被放过；
- 属性文档要说清它关掉的是什么、以及关错了会丢什么（照 `CONFIGURATION.md` 现有那种"什么坏了、怎么看
  出来"的口吻）。

**(B) 根治——把订阅声明与 `@Externalized` 解耦。** 让消费方直接声明订阅哪些 topic，那个注解就不再兼
"发布路由"和"订阅声明"两份差，guard 的门禁也就自然只覆盖发布方。代价要认：需要另一种声明方式（一个
`@Consumed("topic")` 之类的注解，或一份 topic 配置），是契约面的改动，且会与 S21 的契约演进相互影响。

无论选哪个，`CONFIGURATION.md` 的 `messaging.kafka` 一节都该出现"只消费的服务怎么配"这一行——今天没有。

## 复现

`aipersimmon-ddd-samples/s04-integration-events-across-services/inventory-service`：把
`aipersimmon-ddd-outbox-mybatis-plus` 依赖去掉、`flyway.components` 改回 `[inbox]`，启动即失败，异常正文
就是 `:156-163` 那段。
