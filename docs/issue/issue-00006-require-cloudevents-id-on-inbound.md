---
id: issue-00006-require-cloudevents-id-on-inbound
type: issue
role: main
status: resolved
parent: decision-00014-cloudevents-integration-event-contract
---

# 缺 `ce_id` 的入站记录绕过 inbox、并在每次重投补造新身份

CloudEvents `id` 是**必填**属性,也是 inbox 去重键。Kafka 消费桥对缺失 `ce_id` 的记录不但不拒绝,反而
**补造随机身份**并**跳过去重**——同一条记录每次重投都被当作新事件处理,幂等保护形同虚设。正确行为是**拒绝
→ DLT**,而非发明身份。

## 问题(现状,file:line 为证 —— 改造前)

- **等级:Medium**。
- `KafkaIntegrationEventListener.onMessage`:`if (inbox != null && eventId != null && inbox.alreadyProcessed(eventId))`
  —— `ce_id` 缺失(`eventId == null`)时**整段 inbox 去重被跳过**。
- `KafkaIntegrationEventListener.reconstruct`:`orElse(header(record, ID), UUID.randomUUID().toString())`
  —— 缺失 `ce_id` 时**每次重投生成新 UUID**,信封身份随之变化。
- 叠加效应:无 `ce_id` 的记录既不被去重、又每次以新身份出现 → 下游每次都当新事件;且它**成功发布**,毒丸被无限
  重处理(比静默丢弃更糟)。
- 同类"轻症":缺 `ce_type` 时 `reconstruct` 抛的是 `IllegalStateException`(**未被分类为永久**),会白耗
  `max-retries` 次重试才进 DLT——缺 `ce_type` 的记录重投永远还缺,重试无意义。

## 根因(第一性)

信封的 `id` 必须来自生产者写线的 `ce_id`(全局唯一、跨重投稳定),它同时是 inbox 幂等键。**用随机 UUID 兜底**
把"缺少身份"这一**永久性契约违约**误当作"可容忍的缺省",直接摧毁了 at-least-once 下 inbox 赖以工作的前提:
"同一逻辑事件多次投递携带同一 key"。`id` 与 `type` 都是 CloudEvents 必填属性,缺失即**畸形**,应拒绝而非修补。

## 修复

必填 CloudEvents 属性缺失 = 畸形 = **永久失败**,拒绝并 DLT,绝不补造:

1. **`MalformedIntegrationEventException`**(新,`aipersimmon-ddd-integration`,framework-free):缺少/不可解析必填
   属性时抛出,语义为永久失败。
2. **listener 收口**:`onMessage` 顶部 `require(ce_id)`——缺失即抛(在 inbox 检查之前,**不再 `UUID.randomUUID()`
   兜底**);`reconstruct` 的 `ce_type` 缺失也改抛同一异常。二者统一为"缺必填属性 → 畸形 → 永久"。
3. **Kafka error handler**:`MalformedIntegrationEventException` 加入 not-retryable 集 → 畸形记录**首次即进
   `<topic>.DLT`**,不耗退避。
4. **口径统一**:同一异常并入 outbox 侧 `DefaultFailureClassifier` 的永久集,两传输对"何为不可重试"保持一致。

## 验证结果

- `KafkaIntegrationEventListenerTest`:缺 `ce_id` / 缺 `ce_type` → 抛 `MalformedIntegrationEventException`、不发布、
  inbox 不被以假 id 触碰;既有 well-formed 用例不回归。
- `KafkaErrorHandlerTest`:`MalformedIntegrationEventException` 首次即 recover(→ DLT),不重试。
- `DefaultFailureClassifierTest`(新):未知类型 / 畸形 / (包裹的)JSON 解析失败均为 permanent,其余 transient,
  自引用 cause 不死循环。
- 库反应堆全绿;multi-module 脚手架不受影响(进程内 events,不含 Kafka)。

## 影响 / 行为变化(需知会)

- **breaking(消费侧)**:缺 `ce_id` 或 `ce_type` 的入站记录此前会(错误地)被处理或经重试后丢弃,现在**首次即
  dead-letter**。这是刻意的——必填 CloudEvents 属性缺失是永久契约违约。合规生产者(本库发件器始终写全 `ce_*`)
  不受影响。

## 关联

- [[decision-00014-cloudevents-integration-event-contract]] —— `id`/`type` 为必填属性、inbox 键的契约来源。
- [[issue-00003-messaging-delivery-reliability]] —— 本 issue 复用其 Kafka DLT 通道与 not-retryable 永久分类。
- [[issue-00005-integration-event-logical-type-resolution]] —— 同"永久失败 → DLT、不猜测/不补造"的立场(边界 #5)。
