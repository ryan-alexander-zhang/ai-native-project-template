---
id: issue-00143-the-headers-are-checked-and-the-payload-is-trusted
type: issue
role: main
status: open
---

# 信封头严进严出，payload 却是空对象也放行——契约边界只修了一半

2026-07-30 全面评审（P1）。

## 问题

消费桥对 `ce_*` 头严格校验（`KafkaIntegrationEventListener.java:216-246`，缺失/畸形 →
永久失败 → DLT），但 payload `{}` 也能反序列化成功：全部 api record 均无紧凑构造器校验——
`OrderReadyForFulfilment.java:66-70` 仅做防御性拷贝、允许 `lines == null`；`StockReserved`、
`PaymentRequested`、`StockReservationFailed` 等同样裸奔。`orderId=null`/`lines=null` 一路
流到 `OrderReadyForFulfilmentListener.java:65` 的 `event.lines().stream()` 才 NPE。

## 根因（第一性）

- 期望：parse, don't validate——校验在契约边界完成；毒消息被立刻归类为毒消息。
- 分歧机制：NPE 发生在 handler 深处，落入错误处理器的 "ambiguous" 档，先做一轮注定无效的
  指数退避重试才进 DLT——而这本是纯毒消息。
- 真根因：框架对 header 的立场（"fabricating identity would defeat the inbox... reject rather
  than default"）没有贯彻到 payload；api record 被当成了 DTO 而不是契约。

## 复现（先写失败测试）

向 topic 投一条头合法、体为 `{}` 的 `OrderReadyForFulfilment`，断言它**不经重试**直接进
DLT。修复前它先退避数轮。

## 改法

每个 api record 加紧凑构造器（`orderId` 非空、`lines` 非空非空集、`amountMinor >= 0`——
最后一条正是 issue-00075 的教训所在，契约至今没把该范围写成代码）。Jackson 构造失败抛
`ValueInstantiationException`（`JsonProcessingException` 子类），已在不可重试名单
（`AipersimmonDddMessagingKafkaAutoConfiguration.java:340-343`），即刻正确归类。契约同时
获得自文档化。

## 验证结果

未修复。

## 关联

- 失败码可空性矛盾（同为契约两侧失配）：[[issue-00131-one-side-allowed-null-the-other-side-threw]]
