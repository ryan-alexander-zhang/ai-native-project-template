---
id: decision-00020-outbox-engine-over-one-store-port
type: decision
status: active
motivated_by: [report-00003-ddd-library-review-2026-07-29]
---

# outbox 的投递逻辑归入 `-outbox-engine`，两个后端只提供一个 store 端口的适配器

## 结论先行

> **outbox 家族改为 engine-over-store-port**：新增 `aipersimmon-ddd-outbox-engine`，承载 writer、relay、
> 调度触发器、保留期清理与共享的 Spring 装配；当时的 `aipersimmon-ddd-outbox-jdbc` 与
> `aipersimmon-ddd-outbox-mybatis-plus` 只提供 `OutboxStore` 适配器、死信 store 与死信读侧、以及
> ShedLock 的 `LockProvider`。这与 `-process-manager-engine`、`-operation-log-engine` 已经用过两次的形状一致，
> outbox 只是因为早于那次重构而没跟上。

## Context

[[report-00003-ddd-library-review-2026-07-29]] §2 架构层第一条：

> outbox 家族没有 engine 层：relay/retry/backoff/死信/租约轮询——全框架最关键的并发代码——在 jdbc 与
> mybatis-plus 各维护一份（`OutboxRelayScheduler` 除包名外字节相同）。

重构前的实测（`wc -l`）：

| 类 | jdbc | mybatis-plus | 差异 |
|---|---|---|---|
| `OutboxRelay` | 289 | 260 | 仅 4 处 store 操作（selectDue / markSent / scheduleRetry / backoff） |
| `OutboxWriter` | 191 | 183 | 仅 insert 的写法 |
| `OutboxCleanup` | 49 | 48 | 仅 DELETE 的写法 |
| `OutboxRelayScheduler` | 38 | 38 | **除包名外完全相同** |
| 两个 AutoConfiguration | 187 | 213 | 仅 store 类型与 mapper 注册 |

relay 里被复制的那些**判断**，恰好是全框架最难写对的一批：

- 部分失败下的按聚合顺序（失败的那条挡住同 subject 的后续，但已死信的那条不挡）；
- 重试预算不能被「mark-sent 失败」消耗——broker 已经收到了，那不是投递失败；
- 死信搬移自身失败时，要退避但**不**计一次尝试，否则行会越过 max-attempts 被永久搁在表里。

这三条各自都是一个独立 issue 换来的（[[issue-00012-dead-letter-move-failure-backoff]]、
[[issue-00013-mark-sent-failure-not-a-dispatch-failure]]）。存两份的代价不是行数，而是
**任何一次修正都可能只落在一半的部署上**，而两个后端的用户都以为自己拿到的是同一个 relay。

## 选项

**A. 保持两份，靠测试对齐。** 两个模块各有一套等价用例（`OutboxRelayBackoffTest` 等）。
成本低、零迁移。但测试只能证明「今天等价」；它不阻止明天只改一边，也不阻止只给一边加用例。
上面三条 issue 的修复当时就是手工同步到两边的。

**B. 让一个后端依赖另一个。** 例如 mybatis-plus 复用 jdbc 的 relay。最省事，但把
「用 MyBatis-Plus」变成「同时拖进 spring-jdbc + JdbcTemplate relay」，模块语义就假了。

**C. 抽 engine，两个后端各写一个 store 适配器（选中）。** 与 process-manager / operation-log 同形。
成本是一个新模块与一次 import 迁移；收益是那三条判断只有一份，且新后端（未来的 R2DBC、Mongo）
只需回答「怎么读写这张表」。

**D. 抽 engine 且把死信读侧也搬进去。** 否决：`DeadLetters` 是分页查询与 replay，它**就是**存储形状的
（游标用表的自增主键、payload 列刻意不选）。把它塞进 engine 只会让 store 端口长出一堆查询方法。

## 决策与边界

`OutboxStore` 是**唯一**的端口，且刻意窄：`insert` / `findDue` / `markSent` / `scheduleRetry` /
`backOffWithoutAttempt` / `deleteSentBefore`。判据是——**凡是「决定」的都留在 engine，凡是「怎么读写表」的才下沉**。

一个刻意的例外写在端口的 javadoc 里：`findDue` 的谓词不是「怎么读表」，而是**按聚合顺序这条保证本身**
（挡住 subject 更早且正在退避的行）。它仍然被写了两遍 SQL，因为两个后端说不同的查询语言；
契约写在端口的 javadoc 上，等价性由两个模块各自跑的用例守住。这是本次抽取**没有**消除的重复，
必须说出口而不是假装它不存在。

> **后续（[[issue-00108-a-killed-relay-instance-stops-all-delivery]]）**：`findDue` 已变成
> `claimDue(now, maxAttempts, batchSize, OutboxLease)`，并新增 `release`；上面那条「唯一没去重的重复」
> 因此变成了**候选查询**（选聚合队头）。方法数仍是 7，判据未变。租约互斥没有下沉成方言实现——claim 是三条
> 方言无关的语句，理由见该 issue。

> **再后续（[[issue-00111-the-relay-waited-for-each-send-in-turn]]）**：`markSent` 由单 id 改为收 **id 列表**，
> 因为 relay 现在把整批交给传输再一起确认，一轮一次写而不是一行一次写。方法数与判据都没变——
> 「一批已确认的行怎么落库」仍然只是「怎么读写表」。同一次改动在**契约**侧加了 `OutboxDispatcher.beginDispatch`
> 与 `InFlightDispatch`：那是 engine 与传输之间的接缝，不属于 store 端口。

**留在后端的另一件事：ShedLock 的 `LockProvider`。** 它是一张 JDBC 锁表，是真正与存储绑定的东西；
engine 只声明「这次轮询持有租约」（`@SchedulerLock` 在 `OutboxRelayScheduler` 上），
provider 由后端提供。未来一个 Redis 租约的后端因此不必绕过 engine。

**engine 依赖 `-outbox-spring-boot-starter`**（为了 `OutboxProperties` 与派发器选择的装配顺序），
沿用两个后端原本就有的依赖方向，不在本次一并重切模块图——配置键因此一个都没变。

## 后果

- 三个 `-engine` 模块，`-engine` 后缀从「两个例外」变成 outbox/process-manager/operation-log 一致的分层规则
  （[[design-00012-module-naming-and-spring-freedom]] §3.1 的后缀表已把它写成规则的一部分，本次只是多一个实例）。
- 模块数 47 → 48。与报告 §3 第 13 项「收敛到约 20」方向相反**一步**，但那一项的目标是消除
  「一个 42 行接口一个模块」这类碎片，而不是把跨后端共用的运行时压回后端里；engine 层正是收敛的前提——
  第 7、8、10 项（行级 claim、持久化目的地、Kafka 腿流水线化）现在都只需改一处。
  **第 7 项已验证这条**：行级 claim 的全部判断（队头谓词、轮询时间预算、三态 `SENT/RETIRED/HELD`、
  release 的时机）都只写了一遍，两个后端各自只多了「怎么把租约写进这张表」。
- 类名保持 `OutboxRelay` / `OutboxWriter` / `OutboxCleanup` / `OutboxRelayScheduler`，包名从
  `outbox.jdbc` / `outbox.mybatisplus` 变为 `outbox.engine.*`。消费方若直接引用过这些类（脚手架的两个测试、
  otel starter 的一个测试）需改 import；这是尚未上线时该付的价。
- 两个后端的 AutoConfiguration 从 187/213 行降到约 90 行，且**只剩** store/死信/租约三件事。

## 关联

- 父：[[report-00003-ddd-library-review-2026-07-29]]（§2 架构层第一条、§3 第 6 项）
- 同形先例：[[design-00008-operation-log-component]]（operation-log 的 engine 层）、
  [[design-00004-durable-process-manager-runtime]]（process-manager 的 engine 层）
- 命名规则：[[design-00012-module-naming-and-spring-freedom]] §3.1（`-engine` 后缀语义）
- 被本次收拢的三条判断：[[issue-00012-dead-letter-move-failure-backoff]]、
  [[issue-00013-mark-sent-failure-not-a-dispatch-failure]]
