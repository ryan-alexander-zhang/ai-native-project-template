---
id: issue-00142-old-event-versions-never-retire
type: issue
role: main
status: open
---

# 事件版本演化只有一条路：每个历史版本一个 listener，直到永远

2026-07-30 全面评审（P1，框架 TOP 缺失能力）。

## 问题

`IntegrationEventCatalog` 按 `(name, version)` 精确匹配、未注册即 dead-letter（`EventType`
javadoc 明说无隐式回退——这个立场本身正确）。但消费侧没有 upcaster 注册点，版本共存的
唯一写法是 scaffold `OrderReadyForFulfilmentListener` 示范的：每个历史版本一个
`@EventListener` 方法 + 一份手写 upcast，收敛进同一个 `reserve()`。

## 根因（第一性）

- 期望：handler 只面对最新版本；旧版本在契约边界被升格，成本 O(1) 处注册。
- 分歧机制：升格逻辑与消费逻辑住在同一层（listener 方法），每加一个版本，**每个消费者**
  多一个方法；事件契约会活很多年，版本数只增不减，成本线性 × 消费者数。
- 真根因：catalog 只做了"识别"，没做"归一"。

## 复现

不适用（缺失能力类）。验收标尺：v1/v2 双版本场景下，消费方只写一个 handler 方法 + 一行
upcaster 注册，`OrderReadyForFulfilmentVersionsTest` 语义不变仍绿。

## 改法

消费侧 upcaster 注册点，形如：

```java
catalog.map("com.example.ordering.OrderReadyForFulfilment", 1)
       .via(v1 -> new OrderReadyForFulfilment(/* upcast，拒绝编造 deadline 的立场保留 */));
```

未注册版本仍走 dead-letter（保持严进）。scaffold 双 listener 收敛为单 listener + 注册，
作为新的 worked example。

## 验证结果

未修复。
