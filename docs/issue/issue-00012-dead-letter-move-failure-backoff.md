---
id: issue-00012-dead-letter-move-failure-backoff
type: issue
role: main
status: resolved
parent: issue-00003-messaging-delivery-reliability
---

# 死信迁移失败时无退避兜底:放弃行被每轮重投(无间隔忙循环),甚至静默搁浅

[[issue-00003-messaging-delivery-reliability]] 的生产侧加固里,relay 对「放弃」的行(永久失败 / 耗尽重试)
调 `DeadLetterStore.store()` **同事务从 outbox 移入**死信表。但 `handleFailure` 的两条放弃分支**只在
`store()` 成功的前提下**才算数——`store()` 一旦抛异常(死信表不可用/被删/迁移事务失败),既有代码既不退避、
也不兜底。

## 问题(现状,file:line 为证)

- **等级:Medium**(条件触发:死信存储不可用)。
- `OutboxRelay.handleFailure`(jdbc `OutboxRelay.java:130-150`、mybatis `OutboxRelay.java:106-129`,两后端同构):
  - PERMANENT 分支与 `attempts >= maxAttempts` 分支都是 `deadLetterStore.store(...)` 后直接 `return false`,
    **不设 `next_attempt_at`、不动 `attempts`**;只有 transient 分支才 `SCHEDULE_RETRY`(退避)。
  - 若 `store()` 抛异常:异常**冒泡出 `relay()`**(未捕获)→ 整轮 poll 中断,且该行 `next_attempt_at` 仍为
    `NULL`、`attempts` 不变。
- 后果一 **无间隔忙循环**:该行下一轮(1s 后)立即到期、再选、再投、再失败、`store()` 再抛 → **每秒硬撞**
  已不健康的死信存储与(永久失败时)broker,零退避——恰是 issue-00003 退避机制要消除的模式,却在放弃通路上漏掉。
- 后果二 **整轮中断**:`store()` 的异常冒泡使该轮 poll 在此行处终止,其后的行本轮不再处理。
- 复现坐实:`relay()` 直接把 `IllegalStateException: dead-letter table unavailable` 抛出
  (`handleFailure` → `store`),而非优雅处理。

## 根因(第一性)

「放弃」是一个**两步动作**:决定放弃(分类/计数)+ 落实放弃(移入死信)。既有代码把「落实」当作**不会失败**的
终态,于是放弃分支没有留任何失败出口。可靠系统里「移动到别处」本身也是可能失败的 I/O,必须有兜底:放弃**受阻**
时,退回到「有间隔地重试放弃」,而不是「无间隔地重试原动作」或「静默卡住」。

## 修复

`handleFailure` 的两条放弃分支改为经统一的 `deadLetter(...)` helper 落实,helper 捕获 `store()` 失败并兜底
(两后端同构):

1. `store()` 成功 → 行移出、返回「已移出」(其聚合放行)——**行为不变**。
2. `store()` 抛异常 → **不冒泡**(不再中断整轮),改为 `SCHEDULE_BACKOFF`:**只推 `next_attempt_at`(退避),
   不递增 `attempts`**,返回「仍存活」(按 subject 保序阻塞其后续)。原始 dispatch 异常挂到 `addSuppressed`,ERROR
   记录死信迁移失败。
3. **为何不递增 attempts**:放弃分支的 attempts 已达/超阈,若再 `attempts++` 会越过 `max` → 被 `SELECT ...
   attempts < max` 永久排除 → **静默搁浅**(即 issue-00007 提到的 legacy-abandoned 行)。只推 `next_attempt_at`
   使行**保持可选**,按退避节奏持续重试迁移,死信存储恢复后**自愈**(dispatch 永久失败 → `store()` 成功 → 移出)。

新增 SQL/wrapper:`SCHEDULE_BACKOFF`(jdbc,仅 `SET next_attempt_at`)/ 对应 `LambdaUpdateWrapper`(mybatis)。

## 边界

- 只改「放弃受阻」的兜底,不改放弃判定(分类/阈值)与正常退避通路。
- `store()` 成功路径、保序、replay 等既有行为不变(由既有弹性测试守住)。
- 若连 `SCHEDULE_BACKOFF` 这条 outbox 更新也失败(DB 全挂),异常仍会冒泡——此时无能为力,下轮重试,可接受。

## 影响模块

`aipersimmon-ddd-outbox-jdbc`、`aipersimmon-ddd-outbox-mybatis-plus`(各自 `OutboxRelay.handleFailure` +
`deadLetter` helper + 一条只推 `next_attempt_at` 的更新)。无 schema 变更。

## 验收标准(GWT)

- **AC-1**:当 `DeadLetterStore.store()` 失败,`relay()` 不抛出、不中断整轮(优雅处理)。
- **AC-2**:放弃受阻的行不被移入死信、留在 outbox,且 `next_attempt_at` 被推到未来(退避),下一轮立即 poll
  **不**重投它(无每秒忙循环)。
- **AC-3**:该行 `attempts` 不越过 `max`、保持可选,死信存储恢复后能被正常移出(不搁浅)。
- **AC-4**:`store()` 成功的既有行为(移出/保序/replay)不回归;jdbc 与 mybatis-plus 两后端一致(H2 验证)。

## 验证结果

先复现后修,两后端库反应堆全绿。

- **复现(红)**:`OutboxRelayDeadLetterFailureTest`(jdbc + mybatis 各一,`base=max=60000ms` 退避、
  `max-attempts=2`):dispatcher 永久失败 + 注入永远抛异常的 `DeadLetterStore`。修复前 `relay.relay()` 直接抛
  `IllegalStateException: dead-letter table unavailable`(`assertDoesNotThrow` 失败)→ 红。
- **修复后(绿)**:同测试断言——`relay()` 不抛;行仍在 outbox、未死信;`next_attempt_at` 在未来;`attempts < max`
  仍可选;紧接着再 `relay()` 一次 dispatch 次数不增(未忙循环)。即 AC-1/AC-2/AC-3。
- **回归**:jdbc 模块 15 测试、mybatis-plus 模块 10 测试全绿(含各自 `OutboxRelayResilienceTest`/
  `OutboxRelayBackoffTest`,守住 store 成功路径与保序/replay 不回归),`BUILD SUCCESS`。即 AC-4。

AC-1 ~ AC-4 达成,本 issue `resolved`。

## 关联

- [[issue-00003-messaging-delivery-reliability]] —— 本 issue 补齐其放弃通路「迁移也可能失败」的兜底。
- [[issue-00007-ordering-across-backoff-window]] —— 其提到的 legacy-abandoned(`attempts>=max AND sent=false`)
  搁浅正是本修复「只推 next_attempt_at、不递增 attempts」要规避的。
- [[issue-00011-bound-outbox-kafka-send-await]] —— 同批 outbox relay 加固(等待 ack 有界)。
