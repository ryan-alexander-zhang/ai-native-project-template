---
id: issue-00135-three-contexts-publish-under-one-name
type: issue
role: main
status: open
---

# 三个上下文的事件在 wire 上都宣称产自 ordering

2026-07-30 全面评审（P0）。

## 问题

`integration.source: ${spring.application.name}`（scaffold
`start/src/main/resources/application.yml:96-98`，值为 `ordering`）是全应用共享的一个值，
`OutboxWriter`/`SpringIntegrationEvents` 铸的每一个信封都用它。于是
`com.example.inventory.StockReserved` 在 wire 上宣称由 `ordering` 产生。

而 `EventEnvelope.java:14`（aipersimmon-ddd-integration）把 source 定义为
"the context that produced it"——字段含义与实现直接矛盾。

## 根因（第一性）

- 期望：published language 的每个字段含义与实现一致；上下文身份不坍缩为部署单元身份。
- 分歧机制：source 被配置成了 per-deployment 属性，而它声明的语义是 per-context。
- 后果三处：(a) CloudEvents `source` 语义错误；(b) inbox 以 `(consumer, source, message_key)`
  去重——将来按脚手架自己宣传的路径把 inventory 拆成独立服务后 source 变化，迁移窗口内
  新旧 source 的同一事件不互认；(c) 下游按 source 审计/路由的一切消费者从第一天起拿到错值。

## 复现（先写失败测试）

断言 inventory 上下文发布的事件信封 `source` 不等于 ordering 上下文发布事件的 `source`
（或等于其声明的上下文名）。修复前失败。

## 改法

让 source 成为 per-context 属性：各 BC 的发布入口按模块注入不同 source（如
`com.example/inventory`），或框架在 `@EventType`/`@Externalized` 层面支持声明 source。
若最终决定 source 就是部署单元身份，则必须改 `EventEnvelope` javadoc，并写明拆分部署时
inbox 去重键的迁移含义——二者必居其一，现状是两头都不占。

## 验证结果

未修复。
