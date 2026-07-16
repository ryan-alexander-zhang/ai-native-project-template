---
id: issue-00007-ordering-across-backoff-window
type: issue
role: main
status: resolved
parent: issue-00003-messaging-delivery-reliability
---

# 退避窗口内同 `subject` 的后续事件越过失败事件(破坏 per-aggregate 顺序)

[[issue-00003-messaging-delivery-reliability]] 的 outbox 加固(H1)引入指数退避后,留下一个顺序漏洞:relay 的
poll 只选**已到期**行,而失败行的 `next_attempt_at` 被推到未来,于是它**退避期间从批次里消失**;`blockedSubjects`
只在**单批次内**生效,跨轮无法得知该行仍在退避。结果同一 `subject` 的后续事件越过失败事件先行投递,推翻了库所
宣称(Javadoc / Kafka key=subject)的 per-aggregate 顺序保证。

## 问题(现状,file:line 为证)

- **等级:High**。
- 时序:`e1/agg-1` 失败 → `next_attempt_at` 在未来;`e2/agg-1` 立即可投。下一轮 SELECT 排除 `e1`(未到期)、返回
  `e2`(到期);`blockedSubjects` 是新批次的空集,不知道 `e1` 仍在退避 → `e2` 越过 `e1` 投递。
- jdbc(`OutboxRelay` 的 `SELECT_DUE`)与 mybatis-plus(`OutboxRelay` 的查询)两套都有。
- 与 H1 Javadoc / issue-00003 "live 重试行仍按 subject 保序"的声明相反——那句只在单批次内成立。

## 根因(第一性)

顺序不变式是:对一个 `subject`,事件按 `(created_at, id)` 顺序投递,**在 e_n 落地(sent)之前绝不投 e_{n+1}**。
退避把 e_n 移出到期集合,但**并未解除它对后续事件的排序约束**;而约束此前只由内存态的 `blockedSubjects` 表达,
它的生命周期仅一个批次。约束必须落到**查询层**、跨轮持续。

## 修复

在 poll 的 SELECT 加一条**跨轮 head-of-line 约束**(两后端同构;mybatis 用带显式外层别名的 `@Select`
`selectDue`,避免相关子查询的自连接歧义):

> 排除某行,当且仅当存在同 `subject`、更老 `(created_at, id)`、仍 **live**(`sent=false AND attempts<max`)、
> 且**尚未到期**(`next_attempt_at > now`,即退避中)的事件。

要点(全部按评审边界守住):

1. "更老"用 `(created_at, id)` 完整比较(`id` 为 PK,消除同 `created_at` 歧义)。
2. 更老行只要仍 `sent=false` 且在退避,就阻塞——即使它这轮不可投。
3. **due 的**更老行不是阻塞者:它与后续事件同批、按序处理,批次内失败仍由 `blockedSubjects` 兜住(保住热聚合
   单轮吞吐,不退化成"每轮一条")。
4. 死信行已被 H1 移出 outbox,自然解除阻塞;**遗留** `attempts>=max AND sent=false` 行因 `attempts<max` 不算
   阻塞者,不会永久占据 head-of-line(升级前的老行不致死锁其聚合)。
5. 空白 `subject` 与 `null` 一同视为**无排序键**(既不阻塞也不被阻塞),统一 relay 与 Kafka dispatcher 此前对
   空白值的不一致处理。
6. 新增索引 `(subject, sent, created_at, id)` 支撑该相关子查询,避免其成为新扫描瓶颈。

## 验证结果

- jdbc `OutboxRelayBackoffTest#aLaterEventWaitsWhileAnEarlierEventOfTheSameSubjectBacksOff`:`e1` 退避时 `e2` 不投、
  无关聚合 `x1` 不受阻;`OutboxRelayResilienceTest`:死信的更老事件释放其聚合、更老事件成功后后续才放行。
- mybatis-plus `OutboxRelayBackoffTest` 对等用例(验证 `@Select` 的相关子查询真的阻塞)。
- 库反应堆全绿;multi-module 脚手架不受影响(进程内 events)。

## 关联

- [[issue-00003-messaging-delivery-reliability]] —— 退避(H1)引入本漏洞;本 issue 补齐其顺序保证。
