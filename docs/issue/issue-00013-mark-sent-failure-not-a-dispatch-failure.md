---
id: issue-00013-mark-sent-failure-not-a-dispatch-failure
type: issue
role: main
status: resolved
parent: issue-00003-messaging-delivery-reliability
---

# 投递成功但标记 sent 失败被误判为投递失败:重试重复,末次更把已投递消息死信

`OutboxRelay.relay()` 把 `dispatcher.dispatch()` 与随后的 `MARK_SENT` 更新放在**同一个 `try`** 里。二者失败
语义完全不同——前者「消息没发出去」,后者「消息已发出去、只是没记上账」——却共用一个 `catch → handleFailure`,
于是**标记失败被当成投递失败**处理。

## 问题(现状,file:line 为证)

- **等级:Medium**(at-least-once 固有边界之一,但「把已投递消息死信」是尖锐的一角)。
- jdbc `OutboxRelay.java:119-126`、mybatis `OutboxRelay.java:86-97`(两后端同构):
  ```
  try {
      dispatcher.dispatch(message);          // 已 ack:消息确实发出去了
      jdbc.update(MARK_SENT, ...);            // 若这里抛(DB 抖动),被下面的 catch 吞
  } catch (RuntimeException e) {
      handleFailure(pending, e);              // 误当投递失败:分类 / 计数 / 退避 / 死信
  }
  ```
- 后果一 **必然重复**:`MARK_SENT` 失败(DB 抖动)→ `handleFailure` 经 `DefaultFailureClassifier` 判为瞬时 →
  `SCHEDULE_RETRY`(attempts++、退避)→ 下轮**重投一条已投递的消息**,并**错误占用重试预算**。
- 后果二 **已投递消息被死信**:若此时已达 `max-attempts`,走 `RETRIES_EXHAUSTED` 分支——把一条**已成功投递**
  的消息移入死信表(`reason=RETRIES_EXHAUSTED`);运维据此重放 → 再来一条重复。同时错误地按 subject 阻塞其后续。
- 复现坐实:`max-attempts=1` 时单次 `MARK_SENT` 失败即打印
  `outbox dispatch for eventId=e1 failed 1 times; dead-lettered`——一条**已投递**消息进了死信。

## 根因(第一性)

`dispatch()` 成功这一刻,at-least-once 的投递义务**已经完成**;此后只剩「幂等地记录已完成」这件本地账务。把
账务失败塞回投递失败的通路,等于用「重试/放弃投递」去处理一件**投递已不需要重试**的事——既造成重复,又可能把
成功当失败归档。正确姿态:`dispatch()` 之后另起一段,标记失败**只记日志、不改判定**,把行留作未发,依赖下轮
**重投(可接受的重复,由消费侧 inbox 去重)**,绝不死信、不计入预算。

## 修复

拆开两段(两后端同构):

1. `dispatch()` 单独 `try`;失败仍走 `handleFailure`(+ 按 subject 阻塞),然后 `continue`。
2. `dispatch()` 成功后**另起** `try` 做 `MARK_SENT`;失败仅 `log.warn`(不 `handleFailure`、不死信、不计
   attempts、不设 next_attempt_at、不阻塞 subject)。行保持 `sent=false`,下轮自然重投——一条 at-least-once
   重复,消费侧 inbox 去重。

## 边界

- **不消除**重复本身:「已发出但没记上账」→ 下轮重投是 at-least-once 无法原子消除的固有重复(记账与发消息跨两个
  资源),本修复只把它从「死信/误判」纠正为「正常重投」。消费侧幂等仍由 inbox 负责(正交)。
- 极端:若 `MARK_SENT` 对某行**持续**失败(通常意味着 DB 整体异常,SELECT 也会失败),会每轮重投该行(响亮的
  WARN)。相较旧行为「把已投递消息死信」,「留未发 + 告警」更诚实且可自愈,取此。
- 保序不受损:成功投递的行本轮不阻塞其 subject 后续(它已按序投出);下轮的重复晚于后续到达,首投顺序不变。

## 影响模块

`aipersimmon-ddd-outbox-jdbc`、`aipersimmon-ddd-outbox-mybatis-plus`(各自 `relay()` 循环体拆分)。无 schema 变更。

## 验收标准(GWT)

- **AC-1**:`dispatch()` 成功但 `MARK_SENT` 失败时,该消息**不进死信**、**不计入 attempts**、**不排退避**;`relay()`
  不抛、不中断整轮。
- **AC-2**:该行留 `sent=false`,下一轮 poll **重投**它(可接受的 at-least-once 重复),而非丢失或死信。
- **AC-3**:正常成功路径(dispatch + mark-sent 皆成功)行为不变;jdbc 与 mybatis-plus 两后端一致(H2 验证)。

## 验证结果

先复现后修,两后端库反应堆全绿。

- **复现(红)**:`OutboxRelayMarkSentFailureTest`(jdbc + mybatis 各一,`max-attempts=1`):dispatcher 记为已投递
  且成功,`MARK_SENT` 被强制失败(jdbc 用拦截 `UPDATE ... SET sent = TRUE` 的 `JdbcTemplate` 子类;mybatis 用拒绝
  outbox 表 UPDATE 的 H2 触发器)。修复前:死信计数 1(`... failed 1 times; dead-lettered` 一条已投递消息)→ 断言
  `deadLetterCount==0` 失败,红。
- **修复后(绿)**:同测试断言——`relay()` 不抛;死信计数 0;行留 `sent=false`、`attempts==0`、`next_attempt_at==null`;
  紧接再 `relay()` 一次 dispatch 次数变 2(重投,而非丢失/死信)。即 AC-1/AC-2。
- **回归**:jdbc 16 测试、mybatis-plus 11 测试全绿(含各自 resilience/backoff 套件,守住正常成功路径与保序/死信/
  replay 不回归),`BUILD SUCCESS`。即 AC-3。

AC-1 ~ AC-3 达成,本 issue `resolved`。

## 关联

- [[issue-00003-messaging-delivery-reliability]] —— 本 issue 纠正其 relay「dispatch 与 mark-sent 同 try」的误判。
- [[issue-00011-bound-outbox-kafka-send-await]]、[[issue-00012-dead-letter-move-failure-backoff]] —— 同批 relay 加固。
