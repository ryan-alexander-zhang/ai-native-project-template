---
id: issue-00008-strict-inbound-cloudevents-validation
type: issue
role: main
status: resolved
parent: decision-00014-cloudevents-integration-event-contract
---

# 入站 CloudEvents 契约校验不完整(与 ADR "source 必填 / 畸形即刻 DLT" 未对齐)

[[issue-00006-require-cloudevents-id-on-inbound]](M1)强制了 `ce_id` / `ce_type`,方向正确,但入站校验仍有缺口:
部分必填属性被**默认兜底**、部分**根本不校验**、非法值被当**瞬时错误重试**——与 [[decision-00014-cloudevents-integration-event-contract]]
"source 必填"及本库"畸形消息立即 DLT"的语义不一致。

## 问题(现状,file:line 为证 —— 改造前)

`KafkaIntegrationEventListener.reconstruct`:
- `ce_source` 缺失 → 回退 `"unknown"`(ADR 条目 5 说 source 必填)。
- `ce_specversion` **完全不读、不校验**。
- `ce_dataschemaversion` 经 `Integer.parseInt` → 非法值抛 `NumberFormatException`(不在永久集)→ **当瞬时重试**;缺失
  默认 1。
- `ce_time` 经 `Instant.parse` → 非法值抛 `DateTimeParseException`(不在永久集)→ **当瞬时重试**。

`(type, version)` 已被定为**精确契约键**,则"缺 version 默认 1"本质仍是协议 fallback——放松了默认总线协议。

## 根因(第一性)

必填契约属性的缺失/非法是**永久性**畸形,应立即 DLT(承 M1 的立场),不应被默认值掩盖、更不应耗瞬时重试。
第三方/遗留生产者若字段不全,应经 ACL / 适配器补齐内部契约,而不是让默认消费者放宽协议。

## 修复(采用严格契约,无默认协议 fallback)

`reconstruct` 前置校验所有必填属性,任何"缺失或存在但非法"一律抛 `MalformedIntegrationEventException`(永久 → 首次即
DLT,承 [[issue-00006-require-cloudevents-id-on-inbound]] 的 not-retryable 装配):

- `ce_id`:必填(M1 已有)。
- `ce_type`:必填(M1 已有)。
- `ce_source`:**必填,去掉 `"unknown"` 回退**。
- `ce_specversion`:**必填且必须等于 `1.0`**。
- `ce_dataschemaversion`:**必填、整数、`>= 1`**(它是 `(type, version)` 键的一半,不能默认)。
- `ce_time`:**允许缺失**——缺失时优先取 Kafka record timestamp(接收时间近似),再退当前时钟;**存在但非 ISO-8601**
  则畸形。

合规生产者不受影响:`KafkaOutboxDispatcher` 始终写全 `ce_id/ce_source/ce_specversion/ce_type/ce_dataschemaversion`
(及 `ce_time`),生产→消费回环仍通过。

## 验证结果

- `KafkaIntegrationEventListenerTest`:缺 source / 缺或错 specversion / 缺或非整数或 `<1` 的 dataschemaversion / 非法
  time → 均抛 `MalformedIntegrationEventException`;缺 time 仍成功投递(取 record timestamp)。既有 well-formed 用例
  更新为携带 specversion + dataschemaversion,继续通过。
- 库反应堆全绿;multi-module 不涉及 Kafka。

## 关联

- [[issue-00006-require-cloudevents-id-on-inbound]] —— M1;本 issue 补齐其余必填属性的校验并统一"畸形→DLT"。
- [[decision-00014-cloudevents-integration-event-contract]] —— source 必填、`(type, version)` 精确键的契约来源。
- [[issue-00003-messaging-delivery-reliability]] —— 永久失败 → DLT 的 not-retryable 装配复用其错误处理器。
