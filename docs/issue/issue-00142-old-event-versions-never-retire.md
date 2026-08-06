---
id: issue-00142-old-event-versions-never-retire
type: issue
status: resolved
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

2026-07-31 修复。注册点没有采用草案的 builder 式 `catalog.map(...).via(...)`，而是更贴合框架
既有惯用法的**泛型 bean**：`EventUpcaster<From, To>`（接口在 `aipersimmon-ddd-integration`，
零框架依赖——契约模块白名单门禁不受影响），实现为一个 `@Component` 即注册完成——
**(name, v1→v2) 的注册信息全部从两端类自己的 `@EventType` 注解读出**，注册无从与契约漂移，
且启动期逐条校验：两端必须解析为具体注解类（**擦除的类型参数经 `ResolvableType.resolve()`
退回 bound 而非 null**，同 issue-00141 的坑，注册在接口下会静默失效故一律拒绝）、同一逻辑名
（升格是修订跃迁不是事件翻译）、版本严格递增（**这也是链有限性的证明——每跳都爬升，无环
可循**）、同源修订重复注册点名拒绝。

- **应用点**：`EventUpcasterChain`（messaging-kafka，包私有）按源类索引；消费桥
  `reconstruct` 反序列化后逐跳走到最新修订（v1→v2→v3 链式生效），**信封 version 改写为
  实际递交的修订**（信封描述它携带的载荷，与载荷类矛盾的版本号是对每个读者说谎；wire 原
  版本仍在 Kafka 记录上可见）；身份与因果元数据一字不动。未注册版本照旧 dead-letter——
  归一不放松严进。
- **一个草案没预见的坑**：`skip-locally-unhandled` 扫描按 `(name, version)` 精确判断本地
  handler——收敛为单 listener 后 v1 没有自己的 listener，**每条旧修订记录会在 inbox 之前被
  静默跳过**。`skippableAsUnhandled` 现在追问链的**终端版本**：listener 实际见到的才是判据。
  嵌入式 Kafka 测试专门钉住这半个交互（真 v1 记录 + 只有 v2 listener + skip 扫描开启）。
- **框架测试**：`EventUpcasterChainTest` 7 条（双跳链、无升格恒等、终端版本、跨事件拒绝、
  版本不增拒绝、重复源拒绝、擦除拒绝）；`KafkaUpcastIntegrationTest`（嵌入式 broker）——
  v1 记录到达唯一的 v2 listener，字段存续、v2 新增字段不编造、信封版本=2、eventId 原样。
- **scaffold worked example 换代**：`OrderReadyForFulfilmentListener` 双方法收敛为单方法
  （验收标尺：一个 handler 方法 + 一个 upcaster bean）；新
  `OrderReadyForFulfilmentV1Upcaster`——"拒绝编造 deadline"的立场原文保留（值=发布时刻+
  预算，旧报文两者皆无，null 才是 v1 一直的含义）+ "何时删除"与 V1 类同钟；
  `OrderReadyForFulfilmentVersionsTest` 语义不变仍绿（双修订产出同一命令、逐行存续、因果
  上下文两路都在），v1 路径改走 upcaster——与消费桥同一条路。

验证：库全 reactor `clean install` BUILD SUCCESS；scaffold `clean test -pl start -am`
验收 BUILD SUCCESS。
