---
id: issue-00165-a-dead-letters-last-error-drops-the-only-useful-half
type: issue
role: main
status: resolved
---

# 死信的 `last_error` 只记最外层异常，最常见的发布失败因此记成一句空话（P2，可运维性）

2026-08-04 写 S22 的 samples 时撞到（`aipersimmon-ddd-samples/s22-operability-deadletters-retention/ordering-service`）。
**不是正确性缺陷**——消息被正确地移进死信表、原因和尝试次数都对。是**运维证据被截断**：
`reason` 和 `attempts` 之外，`last_error` 是死信行里唯一能回答"到底哪儿坏了"的列，而在
Kafka 发布失败这一最常见形态下它记下的字符串是 `org.springframework.kafka.KafkaException: Send failed`。

## 现象（实测）

sample 让 `@Externalized` 的目标 topic 不存在、broker 关掉自动建 topic，然后驱动 relay。
三次尝试后进死信表，`aipersimmon_dead_letter.last_error` 的完整内容是：

```
org.springframework.kafka.KafkaException: Send failed
```

而同一次失败的真实原因在日志里是两层 cause：

```
org.apache.kafka.common.errors.TimeoutException: Topic s22.ordering.never-provisioned not present in metadata after 5000 ms.
Caused by: org.apache.kafka.common.errors.UnknownTopicOrPartitionException: This server does not host this topic-partition.
```

topic 名字、超时时长、"这个 broker 上没有这个 topic-partition" —— 全部在 cause 链里，
全部没进那一列。运维拿着 `/ops/dead-letters` 的返回，知道"发送失败了"，不知道发去哪儿、为什么。

## 原因（一行）

`aipersimmon-ddd-outbox-engine/src/main/java/com/aipersimmon/ddd/outbox/engine/relay/OutboxRelay.java:472`

```java
private static String summarize(Throwable error) {
    return error.getClass().getName() + ": " + error.getMessage();
}
```

只取最外层。`KafkaOutboxDispatcher` 抛的是 `IllegalStateException("failed publishing outbox message …")`
包着 `ExecutionException`，而 spring-kafka 在同步失败路径上先抛 `KafkaException("Send failed")`
（`KafkaTemplate.doSend`，`aipersimmon-ddd-messaging-kafka/.../KafkaOutboxDispatcher.java:81` 调用点），
两者的 `getMessage()` 都不含任何定位信息。

**这不是"库应该猜"的问题，因为库自己已经有走链的写法**：
`aipersimmon-ddd-outbox/src/main/java/com/aipersimmon/ddd/outbox/DefaultFailureClassifier.java:24-38`
就是有界地走 cause 链（`MAX_CAUSE_DEPTH = 20`、自引用停止）来判断永久/瞬时。判断用得到链，
记录反而不用。

`DeadLetterStore#store` 的 javadoc 把这个参数写成 *"a short description of the final failure (class
and message)"* ——契约本身就只承诺了最外层，所以这也是一处契约与用途不匹配：
`DeadLetter` 的 javadoc 说这一列是给 triage 用的（*"what the last failure said"*），
而 triage 需要的正是被丢掉的那一半。

## 同一形状的第二处

`aipersimmon-ddd-process-manager-engine/src/main/java/com/aipersimmon/ddd/processmanager/engine/relay/ProcessEffectRelay.java:247`

```java
String message = failure.getClass().getName() + ": " + failure.getMessage();
```

同样只取最外层，写进流程实例的失败记录。任何经包装器抛出的效果失败（HTTP 客户端、
命令总线、消息发送）都会留下同样一句空话。

## 建议修法

复用 `DefaultFailureClassifier` 已有的有界走链，把 cause 链摊平成一行写入。列类型是 `TEXT`
（`aipersimmon/db/migration/outbox/postgresql`），空间不是约束；需要约束的是长度上限，
按现有 javadoc 的 "short" 承诺截断（比如 2000 字符）即可：

```java
private static String summarize(Throwable error) {
  StringBuilder text = new StringBuilder();
  Throwable cause = error;
  for (int depth = 0; cause != null && depth < 10; depth++) {
    if (depth > 0) {
      text.append(" <- ");
    }
    text.append(cause.getClass().getName()).append(": ").append(cause.getMessage());
    if (cause.getCause() == cause) {
      break;
    }
    cause = cause.getCause();
  }
  return text.length() > 2000 ? text.substring(0, 2000) : text.toString();
}
```

两处都改，并把 `DeadLetterStore#store` 的 `@param lastError` 从 "class and message" 改成
"the failure and its causes"。

## 验收

- 目标 topic 不存在时进死信的行，`last_error` 含 topic 名与 `UnknownTopicOrPartitionException`；
- 顶层异常本身就有信息时（`UnknownIntegrationEventException`）输出不退化；
- 自引用 cause 不死循环，超长被截断。

S22 的 `DeadLetterTest.therelaySpendsItsAttemptsAndThenMovesTheRowAside` 目前**断言的是现状**
（`last_error` 只有 `Send failed`，且**不含** topic 名），并在注释里指向本 issue。修好后那两条
断言要反过来。

## 解决记录（2026-08-04）

**改法与建议一致，但把重复的那 12 行提成了一个共用工具**——因为"两处各写一遍"正是这个缺陷的成因。

- 新增 `aipersimmon-ddd-core/src/main/java/com/aipersimmon/ddd/core/error/FailureSummary.java`：
  有界走 cause 链（`MAX_DEPTH = 10`、自引用即停）、拼成
  `class: message <- class: message <- …`、截断到 `MAX_LENGTH = 2000`；`null` 入参返回 `"null"`
  而不是抛（在失败路径上会抛的摘要器比一句无用字符串更糟）。放在 core 是因为两处调用点分属
  outbox-engine 与 process-manager-engine，core 是它们唯一的共同祖先。
- `OutboxRelay.summarize`（原 `:472`）与 `ProcessEffectRelay.describe`（原 `:247`）都改为委托它。
- `DeadLetterStore#store` 的 `@param lastError` 从 "class and message" 改成 "the failure and its
  causes"，并说明为什么 causes 才是重点。

**测试**：`FailureSummaryTest` 7 条（单层、cause 链的确切输出、null message、null 入参、自引用不死循环、
深度上界、长度上界）；`OutboxRelayTest.thegiveUpRecordsTheCauseChainAndNotJustTheWrapper` ——
给 `ScriptedDispatcher` 加了 `failingThroughAWrapper` 模式（`IllegalStateException("Send failed")` 包
`IllegalArgumentException("Topic … not present in metadata …")`），断言记录里同时有 wrapper 和 cause。

**验收**：S22 的 `DeadLetterTest.therelaySpendsItsAttemptsAndThenMovesTheRowAside` 原本断言的是缺陷现状
（含 `doesNotContain("never-provisioned")`），现在反过来：`last_error` 同时含 `Send failed`、
`never-provisioned`、`not present in metadata`。这是当初刻意"断言现状而不是断言应然"的收益——修好就打红，
不用靠人记得。`QuarantineController` 的 javadoc 同步改写。

库 full `mvn clean install` 绿。
