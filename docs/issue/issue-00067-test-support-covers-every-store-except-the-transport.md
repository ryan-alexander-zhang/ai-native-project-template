---
id: issue-00067-test-support-covers-every-store-except-the-transport
type: issue
status: resolved
parent: plan-00015-scaffold-depth-and-evaluability
---

# `-test-support` 有 PostgreSQL / MySQL / Redis，唯独没有 Kafka——而 Kafka 是库自己的传输

## 问题（现状，file:line 为证）

- **等级：Low-Medium（不影响正确性，但让被推荐的测试模块在最需要它的那条路上帮不上忙）**。
- `CHOOSING-MODULES.md` 的「Testing」小节把 `aipersimmon-ddd-test-support` 列为推荐依赖：
  "singleton Testcontainers + `@ServiceConnection` configs, so integration tests share one container"。
- 模块实际提供：`PostgresServiceConnection`、`MySqlServiceConnection`、`RedisServiceConnection`、
  `SharedContainers`（postgres / mysql）、`ContainerImages`（`POSTGRES` / `MYSQL` / `REDIS`）。
  **没有 `KafkaServiceConnection`，`ContainerImages` 里也没有 Kafka 镜像。**
- 而跨服务集成事件走 Kafka 是库出厂就有的传输（`aipersimmon-ddd-starter-messaging-kafka`），
  任何验证「outbox → broker → inbox」这条链路的消费方都需要一个 Kafka 容器。
- 实测：样例 G2 改用该模块后，`start/src/test/java/com/example/TestInfrastructure.java` 仍然要**自己声明**
  Kafka 容器并自己钉版本号——而「版本钉在一处、样例不会漂移」正是这个模块存在的理由。

## 根因（第一性）

1. **观察 vs 期望**：期望「推荐的测试模块覆盖库推荐的集成路径」；实际「它覆盖存储，不覆盖传输」。
2. **最小机制**：模块的内容是按**库自己的测试需要**长出来的——`-persistence-*` / `-outbox-jdbc` /
   `-web-store-redis` 各自需要一个数据库或 Redis 容器，于是这三个进了模块。
   `-messaging-kafka` 的测试用的是 embedded Kafka（`spring-kafka-test`），不是 Testcontainers，
   所以库自己从来不需要一个 Kafka 容器配置，模块里也就没有。
3. **真根因**：模块的边界是「库测试用到的容器」，而它对外宣称的边界是「集成测试需要的容器」。
   两者在 Kafka 上分叉，因为库自己在这一处用了另一种测试手段——而那种手段
   （embedded Kafka）恰恰是消费方**不应该**用来验证真实 broker 语义的。
4. **排除的伪根因**：不是消费方用错了 API——模块里确实没有这个类型；
   不是 Testcontainers 不支持——`org.testcontainers:kafka` 的 `KafkaContainer` 正是样例现在用的。

## 复现

不是失败型缺陷。验证即当前状态：`TestInfrastructure` 一半来自
`PostgresServiceConnection`，另一半（Kafka 容器 + 版本 `apache/kafka:3.7.1`）是手写的，
类注释里逐条说明了原因。

## 修复

在 `-test-support` 加 `KafkaServiceConnection`（`@Bean @ServiceConnection KafkaContainer`）
与 `ContainerImages.KAFKA = "apache/kafka:3.7.1"`（与 compose 的 broker 版本一致；镜像名不同是因为
Testcontainers 的 `KafkaContainer` 驱动 Apache 官方镜像而 compose 用 bitnami，wire protocol 相同），
外加 `org.testcontainers:kafka` 依赖。这不要求库自己放弃 embedded Kafka——它是**给消费方**的配置，
和 `RedisServiceConnection` 一样（Redis 也只有 `-web-store-redis` 一个模块用）。
`KafkaServiceConnection` 的类注释把这个分叉写在明面上：embedded 是验证库自身 producer/consumer 接线的
便宜手段，但不是消费方应当用来证明"可靠投递"的东西。

顺带值得复核的同类问题：模块宣称的边界若是「集成测试需要的容器」，那么下一个传输/后端加入时
应当同时进这里，否则同一处分叉会重演。

## 验证结果

**已修复，且比预期多消掉一层。**

- `-test-support` 现有四个 `@ServiceConnection` 配置：Postgres / MySQL / Redis / **Kafka**。
- 样例 `TestInfrastructure` 从"一半手写"变成**整个类只剩两行 `@Import`**——没有 `@Bean`，
  没有镜像名，没有版本号，因此再也无法与库漂移。
- 连带简化：`start/pom.xml` 里 4 个 Testcontainers 相关测试依赖
  （`spring-boot-testcontainers`、`testcontainers:postgresql`、`testcontainers:kafka`、
  `testcontainers:junit-jupiter`）全部删除——它们都随 `-test-support` 传递进测试 classpath。
  样例没有一处再点名 Testcontainers 的坐标或任何镜像 tag。这是本 issue 的真正收益：
  不是"少写一个 bean"，而是"版本钉在一处"这个承诺第一次真的成立。
- 样例 `verify` BUILD SUCCESS（189 项，0 失败），Kafka 容器照旧真实拉起（`DeadLetterReplayTest`
  等 7 个 e2e 类仍走真实 broker）。

## 关联

- [[plan-00015-scaffold-depth-and-evaluability]]（G2：样例改用 test-support 时发现）
