---
id: issue-00009-version-evolution-semantics
type: issue
role: patch
status: resolved
parent: decision-00014-cloudevents-integration-event-contract
---

# `@EventType` 版本演进语义与 `(type, version)` 精确键自相矛盾(文档漏改)

[[issue-00005-integration-event-logical-type-resolution]] 收口时把注册表键定为 **`(name, version)` 精确匹配**
(§边界收口 #3),但注解与 ADR 的**文字**仍停留在早先"按 name 解析、版本是元数据"的模型,声称"同一 `name` 的 v1/v2
解析到同一个类、one handler 服务所有兼容修订"。这与代码相互矛盾。

## 问题(现状,file:line 为证)

- `EventType.java` Javadoc:"`v1` 和 `v2` of one `name` resolve to the **same class**……one handler serve every
  compatible revision"。
- 但 `RegistryIntegrationEventCatalog.lookup(type, version)` 要求**精确**匹配;`OutboxTypeRegistryTest`
  `sameNameDifferentVersionCoexists` 本身就用**两个不同类**(`OrderPlaced` / `OrderPlacedV2`)分别占 `(name,1)` /
  `(name,2)`。
- 一个类只能声明一个 `version`。把同一个类 bump 到 v2 后,历史 v1 消息 `lookup(name, 1)` miss → `UnknownIntegrationEventException`
  → DLT。所谓"同类演进"在默认 catalog 下**不成立**。
- `decision-00014` 条目 2、`issue-00005` §修复 #1、`EventEnvelope` Javadoc 同处措辞一致地错。

## 根因(第一性)

解析键是 `(name, version)` 精确匹配,则 `version` **就是**契约身份的一部分,不是可被"同类兼容演进"透明吸收的元
数据。早先"版本=元数据、按 name 解析"的表述在 §边界收口 #3 改键后未同步,遗留成文档谎言。代码与测试本身自洽,
**要改的是文档**(不回退按-name 解析——那会推翻已确认的边界 #3 fail-fast/精确键)。

## 修复(仅文档,统一语义)

保持 `(type, version)` 精确键不变,把四处文档对齐为同一规范:

> `name` 标识业务事件语义,`version` 标识 payload schema 修订;`(name, version)` 是线上的**精确解析键**。默认扫描
> catalog **一个注解类注册一个 pair**;消费者要继续处理历史版本,须**保留对应类或经自定义 catalog 显式映射**
> (自定义 catalog 可把多个版本映射到同一兼容类),但**无隐式跨版本 fallback**(未注册 pair 即 DLT)。
> bump 规则:**payload schema 变化 bump `version`;业务事实语义变化才换 `name`**。

改动文件:`EventType.java`(类 + `version()` Javadoc)、`EventEnvelope.java`(`version` 字段说明)、
`decision-00014` 条目 2、`issue-00005` §修复 #1。代码零改动(已自洽)。

## 验证结果

- 无代码变更;`OutboxTypeRegistryTest`(按 `(name, version)` 建键、同名异版共存)本就与修正后的文档一致,继续全绿。
- 库反应堆全绿。

## 关联

- [[decision-00014-cloudevents-integration-event-contract]] —— `(type, version)` 契约键的来源;本 patch 对齐其措辞。
- [[issue-00005-integration-event-logical-type-resolution]] —— 引入 `(type, version)` 键;§修复 #1 的遗留措辞在此更正。
