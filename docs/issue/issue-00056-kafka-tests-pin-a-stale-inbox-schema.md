---
id: issue-00056-kafka-tests-pin-a-stale-inbox-schema
type: issue
role: main
status: resolved
parent: report-00001-ddd-framework-review
---

# `messaging-kafka` 的测试固定加载 V1 版 inbox schema，租户迁移 V2 后三个集成测试永久超时

## 问题（现状，file:line 为证）

- **等级：High（不是产品缺陷，但让三个「消费端端到端」测试长期失去意义——它们只会超时，不会检验任何契约）**。
- `aipersimmon-ddd-messaging-kafka` 三个集成测试在 HEAD 稳定失败（隔离单跑也失败，与 `-T1C` 并行无关）：
  - `KafkaDeadLetterIntegrationTest:136 → awaitHandled:169` — `timed out waiting for the consumer to handle g1`
  - `KafkaSystemicFailureIntegrationTest:137 → awaitHandled:156` — 同上
  - `routing/EventRoutingIntegrationTest:151 → awaitSize:173` — `timed out waiting for 1 delivery of A (saw 0)`
- 三者共用 `messaging-kafka/src/test/resources/application.properties:3`，它**只**加载一个迁移文件：
  ```
  spring.sql.init.schema-locations=classpath:aipersimmon/db/migration/inbox/h2/V1__aipersimmon_inbox.sql
  ```
- 但 inbox 的 schema 早已有两个迁移：`inbox/h2/V1__aipersimmon_inbox.sql` 与
  `inbox/h2/V2__add_tenant_id.sql`（后者由多租户 decision-00018 / design-00009 引入）。
- `inbox-jdbc/JdbcInbox.java` 的 `INSERT` 写四列，其中 `tenant_id` 只存在于 V2：
  ```java
  "INSERT INTO aipersimmon_inbox (consumer, message_key, tenant_id, processed_at) VALUES (?, ?, ?, ?)"
  ```
- 于是每条入站记录在 `KafkaIntegrationEventListener.java:133` 的 `inbox.alreadyProcessed(eventId)`
  处抛 `BadSqlGrammarException`（列不存在）。

## 根因（第一性）

1. **观察 vs 期望**：期望「毒消息进 DLT、消费者跨过它继续消费」；实际「分区永久卡在 offset 0，一条都不处理」。
2. **最小机制**：`BadSqlGrammarException` 是 `DataAccessException` 的子类，
   而 `AipersimmonDddMessagingKafkaAutoConfiguration.java:337-344` 的 `isSystemicFailure` 匹配整个
   `DataAccessException` 家族 → 该失败被判为「环境故障」→ 走
   `FixedBackOff(systemicBackoffIntervalMs, UNLIMITED_ATTEMPTS)` → **无限重试、永不 recover、永不进 DLT**。
   日志中 10 秒一次的 `Seeking to offset 0 for partition it-events-0` /
   `Record in retry and not yet recovered` 正是 `systemicBackoffIntervalMs = 10000`
   （`KafkaMessagingProperties.java:92`）的节拍。分区被队首那条记录堵死，后面的 `g1` / `A` 永远到不了 handler。
3. **真根因**：**测试夹具把「一份它并不拥有的 schema」硬编码成了文件清单**。仓库里 10 处
   `spring.sql.init.schema-locations` 中，其余 9 处都由 schema 的**拥有者模块**列出自己的迁移——拥有者加迁移
   和改自己的清单发生在同一个提交里，天然原子。只有 `messaging-kafka` 是**借用方**（它需要真 inbox 来证明
   「毒消息的 inbox 标记随失败事务回滚」）。借用打破了这个原子性：`inbox` 加 V2 时，
   `inbox-jdbc` 和 `inbox-mybatis-plus` 的清单都更新了，而**拥有者无从知道谁抄过这份清单**。
4. **排除的伪根因**：
   - 不是 embedded Kafka 不稳定 / 端口冲突 / 测试间污染——隔离单跑同样稳定失败，且消费者成功
     `Subscribed to topic(s): it-events`、`partitions assigned: [it-events-0]`。
   - 不是 `isSystemicFailure` 把 schema 漂移判为 systemic 判错了。无限重试对 schema 漂移**是可辩护的**：
     补上迁移即自动恢复，且不会把健康消息洪水般冲进 DLT。真正的缺陷是这个无限重试**不可观测**，
     那是另一个问题（见 [[issue-00057-unlimited-systemic-retry-is-invisible]]）。
   - 不是 20 秒超时太短。分区永久堵死，给 20 分钟也一样。

## 复现（test-first）

三个失败测试本身就是复现，且**先于修复存在**（在 HEAD 即红），无需另写：

```
mvn -f aipersimmon-ddd/pom.xml -pl aipersimmon-ddd-messaging-kafka test -Dtest=KafkaDeadLetterIntegrationTest
→ Tests run: 1, Failures: 1 — timed out waiting for the consumer to handle g1
```

它们不是「新写的复现测试」，而是**本应一直在守护这些契约、却因夹具漂移而退化成恒定超时的测试**。修复的验收
就是它们转绿——转绿即证明毒消息真的进了 DLT、消费者真的跨过它、inbox 真的回滚。

## 修复

`messaging-kafka/src/test/resources/application.properties` 改为按目录匹配，而不是列文件名：

```
spring.sql.init.schema-locations=classpath*:aipersimmon/db/migration/inbox/h2/V*.sql
```

选择 glob 而非「补上 V2」的理由：补 V2 只修掉这一次的症状，下一个 inbox 迁移会以完全相同的方式再次腐烂，
而腐烂的表现（无限静默重试）已经证明极难溯因。glob 让借用方**不再持有一份需要同步的副本**。

**只改借用方，不动其余 9 处**：那 9 处列的是自己拥有的迁移，加迁移与改清单同提交、天然原子，没有本 issue 的
失效模式。glob 恰好用在「借用」这个打破原子性的地方，这是有原则的区分，不是风格不一致。

已知边界：`classpath*:` 的资源按字符串排序，`V10` 会排在 `V2` 之前。框架当前单个组件最多 3 个迁移，
到 V10 尚远；在属性文件里就近注明该边界，使其成为一个**可见**的约束而不是隐藏陷阱。

## 验证结果（已修复）

`messaging-kafka` 模块 **43 项测试全绿**（修复前 40 绿 3 红）。三个测试从「跑满 20 秒超时」变成「真的跑完」，
耗时本身就是证据：

| 测试 | 修复前 | 修复后 |
| --- | --- | --- |
| `KafkaDeadLetterIntegrationTest` | 21.99s **FAILURE** | **2.45s** 通过 |
| `KafkaSystemicFailureIntegrationTest` | 超时 **FAILURE** | **6.47s** 通过 |
| `routing/EventRoutingIntegrationTest` | 超时 **FAILURE** | **1.51s** 通过 |

转绿即证明这三条契约现在真的被检验：毒消息被 republish 到 `<topic>.DLT`、消费者跨过它处理了后续的 `g1`、
毒消息的 inbox 标记随失败事务回滚（`inboxCount("p1") == 0`）而 `g1` 的提交（`== 1`）；systemic 失败被无限重试
且从不进 DLT，恢复后被处理；`@Externalized` 事件各自落到自己的 topic 并经桥回本地**恰好一次**。

框架全量 `mvn -f aipersimmon-ddd/pom.xml install`：**BUILD SUCCESS，742 项测试全绿**，Spotless / PMD+CPD /
SpotBugs / JaCoCo 全部通过。这是框架第一次全量绿——此前这 3 项一直红着。

## 关联

- [[report-00001-ddd-framework-review]]
- [[issue-00057-unlimited-systemic-retry-is-invisible]]（同一次排查发现：本 issue 之所以长期隐形的原因）
- [[design-00009-multi-tenancy]] / [[decision-00018-multi-tenancy-pool-model]]（引入 V2 的来源）
- [[issue-00044-integration-events-bypass-outbox-kafka-at-runtime]]（同模块的既有传输选择问题）
