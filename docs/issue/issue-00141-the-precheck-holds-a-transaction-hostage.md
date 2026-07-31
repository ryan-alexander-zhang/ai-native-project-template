---
id: issue-00141-the-precheck-holds-a-transaction-hostage
type: issue
role: main
status: open
---

# 可用性预检在写事务里执行——今天无害，换成 HTTP 那天连接池陪葬

2026-07-30 全面评审（P1）。

## 问题

`PlaceOrderHandler.java:90-96` 的 `stockAvailability.check(skus)` 是同步跨上下文调用，而
`TransactionCommandInterceptor`（order=200，最内层）意味着 handler 的第一行就已在写事务里。
`StockAvailabilityGatewayAdapter.java:18-23` 明言未来"同一接口换成 HTTP client bean，调用点
不改"——届时这个 advisory 预检会拿着数据库连接和事务等一个远程调用，慢库存服务直接放大成
ordering 连接池耗尽。

## 根因（第一性）

- 期望：写事务内不做远程 I/O；fail-fast 预检在事务外完成。
- 分歧机制：拦截器链里没有任何"handler 自声明的事务前步骤"可挂——validation（order=100）
  在事务外，但那是 Bean Validation 专用的。
- 真根因：框架结构性缺口，不是 scaffold 一处笔误。该预检本就是 advisory（javadoc :30-36
  自认权威预留在异步路径），它对事务一致性零贡献，却占着事务。

注：同一 handler 内 `Customer`+`Order` 双聚合写（:129-139）论证充分（信用额度不变量确实
横跨两者、同库），**不在本 issue 范围**，予以认可。

## 复现（先写失败测试）

在 gateway fake 里断言调用时 `TransactionSynchronizationManager.isActualTransactionActive()`
为 false。修复前为 true。

## 改法

任一：

1. 框架加 `PreTransactionCheck`/`CommandEnricher` 扩展点（order 介于 100 与 200 之间），
   让"只读预检"有事务外的家；
2. scaffold 把预检移到入站 adapter（`OrderController` 先查再 `send(PlaceOrder)`）——检查
   本就是 advisory，放边缘不损失一致性。

## 验证结果

未修复。
