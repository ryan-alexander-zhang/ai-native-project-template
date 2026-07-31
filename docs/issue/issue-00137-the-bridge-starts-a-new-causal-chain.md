---
id: issue-00137-the-bridge-starts-a-new-causal-chain
type: issue
role: main
status: open
---

# 域事件跳点上因果链断了，脚手架只好自己起一条新的

2026-07-30 全面评审（P1）。

## 问题

`OrderFulfilmentStarter.java:34-47` 的 `@EventListener` 方法拿不到引发该域事件的命令的
`CommandContext`，`RuntimeOrderFulfilmentProcess.factContext`（:127-129）只好
`CommandContext.root(tenant, "confirmed:" + orderId)` 合成一条新链。于是流程实例的
start/terminal 输入与 `PlaceOrder`/`ConfirmOrder` 的 correlationId 互不相干——框架花大力气
维护的"correlation 全链路贯通"（`CommandBus.java:9-13`）在上下文内部的域事件跳点上断了。

## 根因（第一性）

- 期望：一条业务因果链（下单 → ready → 开流程 → 推进）在观测系统里是一条 correlation。
- 分歧机制：域事件由仓储 `save` 内的 `DomainEvents.publishAndClear` 同步发布，此刻 handler 的
  `CommandContext` 就在调用栈上——但框架没有提供任何取用它的入口，于是订阅者只能合成。
- 真根因：`CommandContext` 的传播机制只覆盖了显式参数传递（`send(command, cause)`），没有
  覆盖"同步调用栈内的隐式当前值"这半边；而框架对 `TenantContext.runAs` 恰恰已经实现了
  同款 scope 机制，只是没有对 `CommandContext` 做。

减刑因素（修复时应保留的正确性质）：确定性 messageId（`fact:orderId`）给 `runtime.start`
换来了免费幂等；租户取 `TenantContext.effective()` 的论证（:120-126）也对。

## 复现（先写失败测试）

发一条 `PlaceOrder`（no-review 路径），取出流程实例的 start 输入的 correlationId，断言等于
`PlaceOrder` 命令的 correlationId。修复前不等。

## 改法

由 `TransactionCommandInterceptor` 或 bus 把当前 context 存入 scope（`TenantContext.runAs`
同款），提供 `CommandContexts.current()` 供域事件订阅者取用；scaffold 的 `factContext`
降级为无绑定时的 fallback。

## 验证结果

未修复。

## 关联

- [[issue-00136-the-second-process-pays-the-boilerplate-again]]
