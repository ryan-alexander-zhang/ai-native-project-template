---
id: issue-00067-test-support-covers-every-store-except-the-transport
type: issue
role: main
status: open
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

## 修复（建议，未实施）

在 `-test-support` 加 `KafkaServiceConnection`（`@Bean @ServiceConnection KafkaContainer`）
与 `ContainerImages.KAFKA`。这不要求库自己放弃 embedded Kafka——它是**给消费方**的配置，
和 `RedisServiceConnection` 一样（Redis 也只有 `-web-store-redis` 一个模块用）。

顺带值得复核的同类问题：模块宣称的边界若是「集成测试需要的容器」，那么下一个传输/后端加入时
应当同时进这里，否则同一处分叉会重演。

## 验证结果

（未修复。样例以手写 Kafka 容器绕行，并在 `TestInfrastructure` 与 `start/pom.xml` 的注释里指向本 issue。）

## 关联

- [[plan-00015-scaffold-depth-and-evaluability]]（G2：样例改用 test-support 时发现）
