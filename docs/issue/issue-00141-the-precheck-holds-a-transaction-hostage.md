---
id: issue-00141-the-precheck-holds-a-transaction-hostage
type: issue
status: resolved
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

2026-07-31 修复，取改法 1（框架扩展点）——根因既然是"框架结构性缺口"，把预检挪去
controller（改法 2）只是把缺口留给下一个 handler，而且每个入站 adapter 都得记得预检，
用例的完整性就散落到边缘了。

- **框架**：core cqrs 新增 `CommandPrecheck<C extends Command<?>>`（`check(command, context)`，
  契约在 javadoc 在案：只读、靠抛异常拒绝、每次 dispatch 都会跑到（含 sendAs 重投）故必须
  可重复、且构造上就是 advisory——检查与提交之间世界会变，不变量仍归其属主强制）。starter
  新增 `PrecheckCommandInterceptor`，**ORDER=150**（validation 100 之后、transaction 200
  之前）；按泛型参数索引（同 handler 解析），懒构建 + `SmartInitializingSingleton` 启动强制
  （同 `RegistryCommandBus` 的 BeanCurrentlyInCreation 论证）；auto-config 无条件注册（空表
  零开销）。**比"判空"更严的注册校验**：擦除的类型参数经 `ResolvableType.resolve()` 会
  **退回 bound（Command 接口本身）而非 null**——注册在接口下的 precheck 永远匹配不上、
  静默失效，所以解析结果非具体类一律启动期拒绝（有测试钉住）。
- **scaffold**：availability 检查从 `PlaceOrderHandler` 整块移入新
  `StockAvailabilityPrecheck implements CommandPrecheck<PlaceOrder>`；handler 失去 gateway
  依赖，双聚合写的论证原样保留（issue 已认可，不在范围）。
- **测试先行（行为红，非编译红）**：`AvailabilityPrecheckTransactionBoundaryTest`
  （ordering-application，手装真 bus + 真事务拦截器 + 边界标记 UnitOfWork）修复前双红：
  gateway 在事务内被调、被拒订单也进了事务；修复后双绿——检查照跑且恰一次、拒绝零事务。
  框架侧 `PrecheckCommandInterceptorTest` 5 条：事务外先于 handler、拒绝不开事务不达
  handler、只筛自己的命令类型、同类型多 precheck 依序首拒即停、不可解析类型启动期炸。
  复现用 UnitOfWork 边界标记代替 issue 原文的
  `TransactionSynchronizationManager.isActualTransactionActive()`——拦截器拥有的事务边界
  就是 `UnitOfWork.execute`，断言同一性质而不必拉起 H2 上下文。

验证：库全 reactor `clean install` BUILD SUCCESS；scaffold `clean test -pl start -am`
验收 89 测 0 败 0 跳（`whenSkuIsUnknown...` 端到端拒绝语义原样通过）。
