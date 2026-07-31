---
id: issue-00143-the-headers-are-checked-and-the-payload-is-trusted
type: issue
role: main
status: resolved
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

2026-07-31 修复，按改法原样落地。三个 api 模块全部 9 个 record（含嵌套 Line/Item）补紧凑
构造器，每模块一个包私有 `Contract.required` 帮手；校验强度按"消费方实际以什么为凭"定：

- id/键类字段一律非空非 blank（orderId、reservationId、paymentOperationId、currency、sku）；
- `amountMinor >= 0`——issue-00075 的两侧范围终于写成一份代码（0 合法、负数拒绝，有测试钉住）；
- `lines` 非空非空集、`quantity >= 1`；**V1 冻结修订同样校验**（它存在就是为了被读，毒 v1
  报文同样要在解析期被拒）；
- **在案取舍**：`code` 必填（机器身份，消费方 evidence type 拒绝空 code）、`reason` 可空
  （人读细节，消费方实证接受 null——把它变成拒绝理由只会把消费方本可处理的消息打进 DLT）；
  `StockQuery`/`StockAvailabilityReport` 空集合法（退化但答案良定义）。

**判类的关键一环**（issue 改法的论证，实测钉住）：紧凑构造器的拒绝经 Jackson 浮出为
`ValueInstantiationException`——是 `JsonProcessingException` 子类，本就在消费桥不可重试
名单上；库侧 `KafkaErrorHandlerTest` 已钉住"cause 链中的 JsonProcessingException → 一次
交付即 DLT"（分类器遍历 cause 链，listener 的 IllegalStateException 包裹不碍事）。

测试先行 + 负向对照都真跑到：

- 单测红先行：3 个 api 模块 `ContractValidationTest`（19 条，含 linchpin：`{}` →
  `ValueInstantiationException` instanceof `JsonProcessingException`），修复前 8 败。
- 端到端 `PoisonPayloadDeadLetterTest`（start，复用既有 context 组不加容器对）：投头合法、
  体 `{}` 的 OrderReadyForFulfilment 上真 Kafka。**负向对照（stash 掉 record 改动实跑）**：
  DLT 头录 `cause-fqcn=java.lang.NullPointerException`、"Cannot invoke List.stream() because
  lines() is null"——issue 描述的深层 NPE 逐字复现，且烧完退避轮才进 DLT（14.4s）；恢复
  修复后 DLT 录解析期拒绝，7.6s 即达（重试轮消失）。

现有生产/测试构造点全量核对（grep 全部 `new <Event>(`），无一依赖被拒绝的形态，零迁移。

验证：3 个 api 模块 + start 全量验收 `clean test -pl start -am` BUILD SUCCESS
（TestContextCountTest 确认 context 数仍为 17）。

## 关联

- 失败码可空性矛盾（同为契约两侧失配）：[[issue-00131-one-side-allowed-null-the-other-side-threw]]
