---
id: issue-00109-a-vanished-route-turned-an-externalized-event-local
type: issue
status: resolved
blocks: [report-00003-ddd-library-review-2026-07-29]
---

# 路由消失时，外发事件被静默改判为本地投递并标记已发送

## 症状

一条本该发到 broker 的集成事件永不到达，且**没有任何迹象**：不抛异常、不进死信、消费延迟看不出来
（那条消息根本没进过 topic），行被标记 `sent=TRUE` 后按保留期删除。

## 成因

reach 由事件类上的 `@Externalized` 注解声明，而 `RoutingOutboxDispatcher` 在**派发时**查表：

```java
Optional<String> topic = routes.topicFor(message.type(), message.version());
if (topic.isPresent()) { externalLeg.dispatch(message, topic.get()); }
else { localLeg.dispatch(message); }        // ← miss 就落这里
```

于是路由是「relay 捞到这行时，当前部署的代码怎么说」的函数，而不是「这条事件当初被写下时决定了什么」。
两者不一致的现实路径至少有两条：

- **版本升级**：保留 v1 事件类以兼容旧消费者，但漏标了 `@Externalized`（或迁移到 v2 时只给 v2 标）。
  `(type, v1)` 从路由表里消失，而库里还躺着写入时确实要外发的 v1 行。
- **滚动发布期间**：新版本删掉了某个 `@Externalized`，新旧实例同时在轮询，谁领到就按谁的表判。
  第 7 项让所有实例并发轮询之后，这一条从「概率事件」变成了「常态」。

miss 落到进程内腿，而进程内投递**会正常返回**——relay 无法区分「投递成功」与「投递到了错的地方」，
于是标记已发送。两个既有启动守卫都看不见这条路径：一个查「有 `@Externalized` 事件时 dispatcher 能否
到达外部」（能，Kafka 在），一个查「durable publisher 是否在位」（在）。它们检查的是部署形状，
而这里坏掉的是**单条行与当前代码的时间错位**。

## 决定

**目的地在写入事务里解析，落成 outbox 行上的一列。** 派发时读列，不再查表。

这不只是"更保险"，它把主场景从"失败"变成"**根本不会发生**"：注解在版本升级中被删掉，已写入的行依然带着
`ordering.events`，照样送到那里。行记得自己要去哪。

配套三件事：

1. **新端口 `EventDestinations`**（在 `aipersimmon-ddd-outbox` core）：`destinationFor(type, version)`。
   `ExternalizedRoutes` 实现它（`topicFor` 随之改名 `destinationFor`——同一个概念一个名字），
   由 messaging-kafka 注册。无传输时是 `ALL_IN_PROCESS`，**那是真话而非降级**：没装传输的部署里
   本来就没有外部目的地。
2. **`OutboxWriter` 的 `destinations` 参数没有 defaulting 重载**。理由与 `idGenerator` 那条一样
   （`issue-00053` 立的先例）：一个悄悄退回 `ALL_IN_PROCESS` 的 writer 会把每一行都盖成本地，
   于是**所有**外发事件静默本地化——比原 bug 严重一个数量级。没有传输的部署显式传 `ALL_IN_PROCESS`。
3. **relay 层一条新不变式**：带目的地的行**不得**交给 `reachesExternalTargets() == false` 的
   dispatcher。这个接缝早就在了，它的 javadoc 描述的正是这类失败——"a dispatch that returns normally
   is indistinguishable from a dispatch that delivered"。现在它有了第二个用途：不只在启动时问一次，
   而是逐行守住。

## 死信重放是第二道门

`DeadLetterStore.replay` 把行从死信表**拷回** outbox。若目的地只落在 outbox 表上，重放一条外发事件
会让它以「无目的地」的身份回来 → 进程内投递 → 标记已发送。同一个 bug，换个入口。

所以 `aipersimmon_dead_letter` 也加这一列，`store` 与 `replay` 两侧都搬运它。两个后端各有一份
`replay` 实现，因此两边各有一条用例钉住它。

## `UnreachableDestinationException` 刻意归为**瞬态**

它不在 `DefaultFailureClassifier` 的永久集合里，所以会退避重试、耗尽 `max-attempts` 后进死信。
永久失败会立刻放弃，而"传输不在了"常常是**一个时间窗而非判决**：滚动发布中部分实例还带着它，
或者运维正要改回配置。重试的代价只是几行日志；立刻放弃则丢掉了下一分钟本可投出的消息。
两条路的终点都是可见的——死信表里，而不是一行悄悄标记 `sent`。

## 顺带简化

`RoutingOutboxDispatcher` 不再需要 `ExternalizedRoutes`，构造参数从 3 个降到 2 个。
`ExternalizedRoutes` 现在只服务两件事：writer 的查询（经 `EventDestinations`）与消费桥的订阅集
（`topics()`）。热路径也少一次 map 查找。

## 落地

- 迁移 `V5__destination_on_the_row.sql` × 3 方言：`aipersimmon_outbox` 与 `aipersimmon_dead_letter`
  各加 `destination VARCHAR(255)`（NULL = 进程内，即无 `@Externalized` 的默认 reach）。不建索引：
  它不参与任何谓词，只是随行读出。
- `OutboxMessage` 与 `OutboxInsert` 各加一个组件；两个 store、两个死信 store 读写它。
- `OutboxRelay.dispatch` 首行加不变式检查；`OutboxWriter.write` 盖章。

## 验证

`OutboxDestinationTest`（jdbc 5 例）：writer 把解析结果写进行；未外发的事件记 NULL；带目的地的行遇到
到不了外部的 dispatcher 时**不被投递、不被标记已发送**、计一次尝试并排定重试；耗尽尝试后成为死信且
`last_error` 里带着那个到不了的目的地；死信带着目的地、重放后目的地回到 outbox 行上。

MyBatis 侧 3 例：writer 盖章、未外发记 NULL、死信 + 重放不丢目的地（那是第二份 `replay` 实现）。

`RoutingOutboxDispatcherTest` 重写：按行上的目的地路由，并新增一例
`theStoredDestinationWinsOverWhateverTheCurrentRoutingTableWouldSay`——router 构造时**根本不传路由表**，
这正是要点。`EventRoutingIntegrationTest`（嵌入式 broker 端到端）的行现在像生产一样自带目的地。

库 + 脚手架两个 reactor `mvn clean verify` 全绿。

## 关联

- 父：[[report-00003-ddd-library-review-2026-07-29]]（§2 Outbox「静默丢失」那条、§3 第 8 项）
- 前置：[[decision-00020-outbox-engine-over-one-store-port]]（判断收拢到 engine）、
  [[issue-00108-a-killed-relay-instance-stops-all-delivery]]（并发轮询把「滚动发布期间按谁的表判」
  从概率变成常态）
- 被修正的设计：[[design-00006-integration-event-routing]] §4.2（reach 在派发时决定 → 在写入时决定）
- 同类先例（响亮失败取代静默降级）：[[issue-00107-silent-degradations-become-loud-failures]]、
  `issue-00053`（`idGenerator` 无 defaulting 重载）
