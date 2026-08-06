---
id: issue-00108-a-killed-relay-instance-stops-all-delivery
type: issue
status: resolved
parent: report-00003-ddd-library-review-2026-07-29
---

# 被杀掉的 relay 实例会让全体停止投递，而那个旋钮同时管着两件事

## 症状

`kill -9`、OOM-kill 或掉节点之后，**所有**集成事件投递静默停摆，最长 60 分钟；期间无异常、无日志、无死信。

## 成因

relay 的互斥只有一层：`OutboxRelayScheduler.poll()` 上的 `@SchedulerLock`。

1. ShedLock 在任务正常或异常返回时都会在 finally 里解锁。进程被杀时没有人执行那个 finally，
   `shedlock` 表里 `lock_until = now + lockAtMostFor` 就那样留着。
2. 其余实例的 `@SchedulerLock` 看到锁被持有便**静默跳过**——这是 ShedLock 的设计，不是 bug。
   于是没有任何实例进入过 `relay()`。
3. `lockAtMostFor` 的下界不是随便选的：它必须盖住**最长一次合法轮询**，否则活着的实例轮询到一半租约
   过期，第二个实例会整批重复投递同一批行（行上没有任何保护）。默认值 `PT60M` 正是被最坏批次预算顶
   上去的——`batch-size 100 × producer.send-timeout-ms 30s = 50min`。

所以一个旋钮同时决定「一次轮询最多能跑多久」与「崩溃后多久有人接手」，而这两件事想要的方向正好相反。
[[report-00003-ddd-library-review-2026-07-29]] §2 把它列为 relay 最大的架构弱点。

顺带的第二个后果：投递吞吐永远是单实例的。1 小时故障积压 180 万行要排约 5 小时，加机器没用。

## 决定

把互斥从**调度**移到**行**，并撤掉 relay 的 `@SchedulerLock`。

撤锁不是可选项，是这条修复的全部意义所在：只加行级 claim 而保留 60 分钟的调度锁，崩溃后依然没有实例
在轮询，压根走不到 claim 查询——行租约设多短都一样停摆 60 分钟。反过来，一旦每行各自被 claim，
「第一个实例还在干活时第二个开始轮询」就是安全的，它只会领到别的行。两者互为前提。

用户 2026-07-29 在两个方案间选定此项；另一个方案是保留 `@SchedulerLock` 但把 `lockAtMostFor` 缩到
`PT2M`（改动更小、有序性推理完全不变，但投递吞吐永远锁死在单实例）。

## 有序性：并发 poller 会破坏它，所以谓词必须收紧

「按聚合有序」此前一半靠「只有一个 poller」撑着。原来的 due 查询只在**更早的同 subject 行正在退避**
（`next_attempt_at > now`）时挡住后来的行；更早的行只要也到期，两条就一起进这一批，靠批内的
`blockedSubjects` 记账维持顺序。这套推理活在一个节点的内存里，并发 poller 一到就不成立：A 领到 e1、
B 领到 e2，B 可能先投出 e2。

收紧后的判据是**只有队头可领**：

```
可领 = 未发送 AND attempts < max AND 已到期 AND 无活跃租约
       AND （subject 为空 OR 不存在更早的仍存活同 subject 行）
存活 = 未发送 AND attempts < max
```

于是同一 subject 在**全局**至多一行在飞（队头是单行，只有一个 claim 能赢），后继行只在队头被发送或
被死信（离开表）之后才可领。这比原来更强：保证不再依赖「只有一个 poller」这个前提，而是行谓词本身的
性质。`blockedSubjects` 因此成了不可达代码，删掉——一个存在于单节点内存里的保证，本来就撑不住并发。

两条刻意保留的边界：`attempts >= max` 的行不算存活（它本该已被死信；一条搁死的行不能永久堵住整个聚合）；
`subject` 为空/空白的行没有排序键，既不挡人也不被挡。

**没有修的一条**：排序键是写入时间，一个 `created_at` 更早但提交更晚的事务仍可能被超越。这是「用时间戳
给 outbox 排序」的固有性质，与本次改动无关，写在 `OutboxStore#claimDue` 的 javadoc 里而不是假装不存在。

## 轮询预算：让租约长度只管一件事

只加租约还不够。租约若短于一次轮询的实际耗时，别的实例会重领我正在投的行——那只产生重复
（at-least-once，消费方 inbox 去重），**不会**乱序（重领的是同一行，它的后继仍被挡着）。但默认
`batch-size 100 × 30s` 意味着要避免重复就得把租约又设回 50 分钟，耦合原地复活。

所以 relay 自己给轮询设界：**过了半个租约就停手，把已领未投的行 release 掉**。于是

- `batch-size` 界定一次轮询做多少活；
- `relay.lease-duration` 界定崩溃后多久有人接手；
- 「一次轮询不会活过自己的租约」由构造保证，前提只剩一条——**单次投递要短于半个租约**。

那条前提正是 Kafka 启动守卫该看的东西，于是它的算式里**`batch-size` 整项消失**：原来是
`batch-size × send-timeout > lock-at-most-for` 才 WARN，现在是 `send-timeout > lease/2`。出厂默认
30s 对 2m30s，宽裕。这就是「解开耦合」在代码里的样子。

`release` 因此进了端口：没有它，一行被放下后要等满租约才能再被领，而它是队头，会连着整个聚合一起等。
mark-sent 失败那条路径同样调它——投递已成功、记录失败的行应当下一轮就被重投（javadoc 一直这么承诺），
而不是等 5 分钟。

## 落地

- 迁移 `V4__relay_row_lease.sql` × 3 方言：`lease_owner` / `lease_token` / `lease_until`
  （沿用 process-manager 的 `lease_*` 命名，同一个概念一个名字），加一条 `lease_token` 索引。
  只加这一条：claim 之后按 token 读回自己赢到的行不能全表扫；`lease_until` 刻意不建索引，
  这是写热表，每条索引都由业务事务在 insert 时付账。
- `OutboxStore`：`findDue` → `claimDue(now, maxAttempts, batchSize, OutboxLease)`，新增
  `release(List<String>)`；`markSent` / `scheduleRetry` / `backOffWithoutAttempt` 一并清租约
  （每一次 relay 写入都终结一个 claim，所以清理挂在它们身上而不是靠各调用点记得）。仍是 7 个方法。
- claim 是三条**方言无关**的语句：选队头候选（`LIMIT`）→ 按 id 列表原子打租约（重查到期与无租约，
  故两个实例的候选集重叠时输家更新到 0 行）→ 按 token 读回。**没有用 `FOR UPDATE SKIP LOCKED`**，
  与报告的建议不同：SKIP LOCKED 相对条件 UPDATE 的收益是高争抢下少做无用功，而队头 claim 下争抢者
  只有实例数那么多，输一次只是一条 0 行的 UPDATE；代价则是每个方言一份 SQL。报告自己那条
  「process-manager 的 SKIP LOCKED claim 在两个真实数据库上零测试覆盖、MariaDB 误判即每轮语法错误
  且 effect 永不投递」就是这个代价的实证。条件 UPDATE 在 H2 上可测，跑的就是生产那条路径。
- claim 的 UPDATE 刻意**不**重查队头子句：曾是队头的行仍是队头，因为更早的行只会离开存活集合
  （唯一例外是上面那条时间戳固有性质）。
- 轮询变多轮：只有队头可领，一次 claim 每个聚合至多一行，所以每投完一行就再 claim（后继成了新队头），
  直到批额用尽 / 无可领 / **有行留在表里且立刻可再领**。最后这条是防热循环——退避为 0 的重试行会立刻
  又到期，否则同一次轮询就把它的 attempts 烧光。死信行不属于此类（已离开表），所以放弃一条消息仍能在
  同一轮里放行该聚合的下一条。这三态就是 `Outcome.SENT / RETIRED / HELD`。
- ShedLock 仍留给 cleanup（删数据该只有一个实例做），`@EnableSchedulerLock` 的默认值改从
  `cleanup.lock-at-most-for` 取；`relay.lock-name` 与 `relay.lock-at-most-for` 两个键删除。

## 刻意没做：relay 的逐行写入没有 token 栅栏

`markSent` / `scheduleRetry` / `backOffWithoutAttempt` 只按 `event_id` 定位，不校验
`lease_token = 我的`。process-manager 的对应写入是有栅栏的，这里没有，代价要说清楚：

设 A 领到行 R，A 的租约过期，B 重领并开始投递；此时 A 的投递失败并调 `scheduleRetry`——它会把
`attempts` 加一并清掉**B 的**租约。后果有两条：多记了一次尝试（极端下可导致比 `max-attempts` 更早死信），
以及 B 还在投的那一刻 R 又变可领（多一条重复）。都在 at-least-once 之内，不会乱序（重领的是同一行，
后继仍被队头挡着）。

不加栅栏的理由是：这个竞态的前提是**租约在轮询中途过期**，而轮询自带「半个租约」的预算，
使它在配置正确时结构上不可能发生——唯一的破法是单次投递超过半个租约，而那正是 Kafka 启动守卫盯着的
那条不变式。加栅栏要把 token 穿进四个签名，并且要处理「栅栏未命中时日志已经宣称重试已排」的分支；
换来的是防住一个已被守卫拦住的场景。若将来 `send-timeout` 与租约的关系不再由守卫看着，这条要重新评估。

## 验证

`OutboxRelayClaimTest`（两个后端各一份，jdbc 7 例 / mybatis 4 例）：同刻并发 claim 取到不相交的行；
被杀实例的行在租约到期后可再领且新持有者拿到 token；更晚的同聚合事件在队头存活期间任何实例都领不到，
队头 sent 后立刻可领；每一种 relay 写入都清掉租约、release 的行立刻可领而退避的行仍等下次尝试；
一次轮询按序投完同一聚合的三条（证明队头 claim 没有牺牲热点聚合的吞吐）；轮询用尽时间预算后把没碰过的
行交还且另一实例能立刻接手。

既有用例一条断言都没改，除了两处必须改的：

- `OutboxRelayScheduleTest`（两份）原来断言 `shedlock` 里出现 relay 锁行，现在断言**相反**——
  默认调度照样排空 outbox，且不留下任何 relay 锁行，附带说明为什么（被杀的实例解不开锁，其余实例会
  一路跳过轮询直到锁过期）。
- mybatis 的 `OutboxRelayMarkSentFailureTest` 用一个「拒绝所有 UPDATE」的 H2 触发器伪造 mark-sent
  失败，而 claim 现在也是 UPDATE，于是它拦错了目标。改成只拦把 `sent` 置为 TRUE 的那条（`sent` 的列
  序号在 `init` 里查一次而非硬编码），这正是它自己注释本来声称的行为。若连 release 也一起拦，那条
  「下一轮重投」的断言反而会失败——因为 release 失败的行确实要等满租约，这个细节值得被测试点住。

库 + 脚手架两个 reactor `mvn clean verify` 全绿。

## 关联

- 父：[[report-00003-ddd-library-review-2026-07-29]]（§2 Outbox 第一条、§3 第 7 项）
- 前置：[[decision-00020-outbox-engine-over-one-store-port]]（判断收拢到 engine 之后，这一项只需改一处）
- 被改写的两条既有判断：[[issue-00013-mark-sent-failure-not-a-dispatch-failure]]（失败后改为 release
  而非等租约）、[[issue-00012-dead-letter-move-failure-backoff]]（死信搬移失败仍退避不计次，且返回 HELD）
- 被本项作废的预算约束：[[issue-00050-outbox-relay-budget-and-config-validation]]、
  [[issue-00011-bound-outbox-kafka-send-await]]（`batch-size × send-timeout` 不再进入租约算式）
- 同形先例：[[design-00004-durable-process-manager-runtime]]（process-manager 的 `lease_*` 列与代际栅栏）
