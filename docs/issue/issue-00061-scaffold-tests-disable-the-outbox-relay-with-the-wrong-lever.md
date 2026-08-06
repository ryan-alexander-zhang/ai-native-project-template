---
id: issue-00061-scaffold-tests-disable-the-outbox-relay-with-the-wrong-lever
type: issue
status: resolved
parent: report-00001-ddd-framework-review
---

# 样例 4 个测试类仍用 `poll-delay-ms` 当调度开关：issue-00059 的修复没有回流到样例

## 问题（现状，file:line 为证）

- **等级：Low-Medium（当前不产生红灯，但它是刚刚被判定为错误的写法，而样例正是使用者照抄的对象；
  同时它让这 4 个测试类的"安静"意图落空）**。
- 四处，均在 `aipersimmon-ddd-scaffold/multi-module/start/src/test/java/com/example/`：
  - `OutboxAtomicityTest.java:38`
  - `ConcurrentAggregateWriteTest.java:46`
  - `MybatisPlusInterceptorCompositionTest.java:39`
  - `AggregateIdIsTimeOrderedTest.java:39`

  写法一致：`"aipersimmon.ddd.outbox.poll-delay-ms=3600000"`，意图是"让后台 relay 在测试期间不要跑"。
- 库侧对同一写法的判决见 [[issue-00059-outbox-relay-tests-race-the-startup-poll]]（已 `resolved`）：
  `@Scheduled(fixedDelay)` 是**先执行、再等待**，把 delay 调大只推迟第二次轮询，启动时那一次照跑。
- 库已在 `e4d6596` 给出正确的杠杆：把调度从 relay 里拆出为
  `OutboxRelayScheduler`（`aipersimmon-ddd-outbox-mybatis-plus/.../OutboxRelayScheduler.java:22-37`，
  类注释逐字解释了为什么 `poll-delay-ms` 不是开关），并由
  `aipersimmon.ddd.outbox.relay.enabled`（`AipersimmonDddOutboxMybatisPlusAutoConfiguration.java:157`）
  条件化它。库自己的 7 个测试类已全部改用新杠杆，**样例的 4 处没有一起改**。
- 同一批测试类里还有 `aipersimmon.ddd.process-manager.jdbc.*` 的 1h 设置，那是另一个根因，
  见 [[issue-00060-scaffold-tests-set-a-process-manager-prefix-that-does-not-bind]]；
  process-manager 侧的正确杠杆同样是 `effect-relay.enabled` / `deadline-worker.enabled`
  （`AipersimmonDddProcessManagerAutoConfiguration.java:261,302` 的 `@ConditionalOnProperty`）。

## 根因（第一性）

1. **观察 vs 期望**：期望"把 `poll-delay-ms` 设成 1 小时 ⇒ 测试期间后台不轮询"；
   实际"上下文启动即轮询一次"。机制与 issue-00059 完全相同，不重复展开。
2. **真根因是修复的传播边界**：issue-00059 的分析范围锁在"outbox 两个后端的 7 个测试类"，
   修复也只覆盖了那 7 个。样例是库的**第二个消费者**，它复制了同一写法，却不在那次
   grep 的范围内。缺的不是判断，是"同一反模式在仓库里还有哪些副本"这一步。
3. **为什么这里没像库里那样变红**：这 4 个测试都不在测试体里直接调 `relay()`——它们只是不希望后台
   有动静。启动那一次轮询扫到的行会被真的投递出去，但没有断言依赖"这些行还没被投递"，
   所以竞态存在而后果不可见。**这使它比库侧那次更危险**：库侧至少会间歇性变红，这里不会。
4. **排除的伪根因**：不是样例配置写错前缀（`aipersimmon.ddd.outbox.poll-delay-ms` 是真实存在且
   绑定成功的键，见 `OutboxRelayScheduler.java:30`）；键是对的，语义是错的——它控制节奏，不控制开关。

## 复现

无法用当前样例断言直接复现（见上文第 3 点：后果不可见）。按 `docs/issue/README.md` 的约定，
记录改用的最强验证：

- **语义钉死测试**（本 issue 的回归守卫，先写后改）：在 `start` 模块断言
  `aipersimmon.ddd.outbox.relay.enabled=false` 时上下文中**没有** `OutboxRelayScheduler` bean，
  而只设置 `poll-delay-ms=3600000` 时该 bean **依然存在**。后半条正是当前 4 个测试类所处的状态，
  它把"delay 不是开关"从注释变成断言。
- **静态验证**：全仓 grep `poll-delay-ms=3600000` / `poll-delay=1h`，确认改后 0 命中，
  即这一反模式在仓库里再无副本。

## 修复

1. `[scaffold]` 4 处 `aipersimmon.ddd.outbox.poll-delay-ms=3600000` →
   `aipersimmon.ddd.outbox.relay.enabled=false`。
2. `[scaffold]` 同 4 处（以及另外 7 处只想"安静"的 process-manager 设置）的
   `effect-relay` / `deadline-worker` 由调节奏改为 `enabled=false`；确实需要快跑完流程的
   e2e 测试保留 `poll-delay=200ms`（那是节奏诉求，用节奏参数是对的）。
3. `[scaffold]` 留下上面的语义钉死测试。

## 验证结果

修复提交 `079dbef`（与 issue-00060 同批：两者改的是同一批测试类里的同一段属性块，
拆成两次提交只会产生一个谁也不想要的中间状态）。

- **语义钉死测试两半都绿**：`BackgroundWorkerControlTest`
  - `WhenTheWorkersAreTurnedOff#theOutboxRelayIsNotScheduled` —— `relay.enabled=false` 下
    上下文中 `OutboxRelayScheduler` bean 数为 0；
  - `WhenOnlyTheDelayIsRaised#theOutboxRelayIsStillScheduled` —— 只把 `poll-delay-ms` 提到 1 小时时
    该 bean **依然存在**。这一半把"delay 控制节奏、不控制开关"从注释变成断言，
    也正是修复前那 4 个测试类的真实状态。
- **静态验证**：`grep -r 'poll-delay-ms=3600000'` 在样例代码中只剩守卫测试里那一处**反例**，
  业务测试中 0 命中。
- **改法**：4 处 `outbox.poll-delay-ms=3600000` → `outbox.relay.enabled=false`；
  11 个类里只求"安静"的 `effect-relay` / `deadline-worker` 一并改为 `enabled=false`；
  7 个确实需要流程快跑完的 e2e 测试保留 `effect-relay.poll-delay=200ms` 与
  `outbox.poll-delay-ms=200`——那里诉求是节奏，用节奏参数是对的。
- **全量**：`mvn -f aipersimmon-ddd-scaffold/multi-module/pom.xml verify` **BUILD SUCCESS**，
  既有断言一行未改仍绿。

## 关联

- [[issue-00059-outbox-relay-tests-race-the-startup-poll]]（同一反模式的库侧实例，已修）
- [[issue-00060-scaffold-tests-set-a-process-manager-prefix-that-does-not-bind]]（同一批测试类的另一处失效）
- [[plan-00015-scaffold-depth-and-evaluability]]（批次 E2）
