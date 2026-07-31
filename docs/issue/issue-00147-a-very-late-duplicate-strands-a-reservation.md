---
id: issue-00147-a-very-late-duplicate-strands-a-reservation
type: issue
role: main
status: open
---

# inbox 窗口外的极晚重复，会留下一笔永远无人释放的预留

2026-07-30 全面评审（P2）。

## 问题

`ReserveStockHandler.java:93-131` 没有任何按 `orderId` 的业务级去重；防线只有 inbox 的
传输级去重。而 `application.yml:169-174` 自己注释道：retention 必须超过 broker 最长重投，
否则极晚重复会被第二次处理——"这正是 inbox 唯一要防的事"。

一旦发生：第二次全额扣减 + 第二个 `Reservation`；流程已在 `AWAITING_PAYMENT`，第二个
`StockReserved` 被 ignore，**该 reservation 永远无人释放**——滞留库存，无报警。

## 根因（第一性）

- 期望："可补偿动作依赖传输去重"这一设计立场的前提是：重复的代价是"多做一次可补偿的动作"。
- 分歧机制：这里的失败模式不是多做一次动作，而是**泄漏一份无人认领的资源**——与立场的
  前提不符。对照：payment 侧为不可逆动作配了 `paymentOperationId` 业务幂等（"decide once,
  announce every time"），inventory 侧的这份资源同样值得同级防护。
- 真根因：`reservation` 表没有把"一张订单一笔预留"这个业务事实写成唯一约束。

## 复现（先写失败测试）

绕过 inbox（模拟窗口外重复）对同一 `orderId` 投两次 `OrderReadyForFulfilment`，断言库存
只扣一次、只存在一笔 reservation、第二次重发同一 `StockReserved`。修复前扣两次。

## 改法

`reservation` 表对 `(tenant_id, order_id)` 加唯一约束；冲突时读出已存 reservation、重发
`StockReserved`——正好是 payment 侧同一形状，两个上下文互为对照教材。

## 验证结果

未修复。

## 关联

- 传输级去重的另一缺口:[[issue-00129-in-process-redelivery-was-not-deduplicated]]
- 唯一约束属于 schema 兜底同类：[[issue-00146-the-flagship-invariants-have-no-last-line]]
