---
id: issue-00147-a-very-late-duplicate-strands-a-reservation
type: issue
role: main
status: resolved
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

2026-07-31 修复，形状与 payment 侧完全同构（"decide once, announce every time"）：

- **handler 先查后决**：`ReserveStockHandler` 开头
  `reservations.findByOrderId(orderId)`——命中（无论是否已 released）即原样重发
  `StockReserved(orderId, 既有 reservationId)` 并返回；每次投递都作答，因为 at-least-once
  的前提就是上一次公告可能没到。端口 `Reservations` 新增 `findByOrderId`（含"一单一预留是
  业务事实"的 javadoc），`MyBatisReservations` 提取共用 `reconstitute(header)`。
- **schema 最后一道线**：迁移 `V2_5__one_reservation_per_order.sql` 把 V2_4 的普通索引
  `reservations_by_order` 替换为 **UNIQUE INDEX `(tenant_id, order_id)`**（同名，读路径不变）
  ——两个并发投递赛过 lookup 窗口时，输家 insert 回滚、重投后命中赢家的行。
- **测试**（都在 `StockReservationAtomicityTest`，复用其真 bus + 真 PG 基建）：
  - `aRedeliveredReservationHoldsNothingTwiceAndReAnnouncesTheSameReservation`：同 orderId
    直接经 bus 投两次（= inbox 窗口外重复的形状）。修复前红：**库存扣两次
    （expected 8 was 6）+ 两笔 reservation + 两个不同 reservationId**；修复后：扣一次、
    一笔、两次公告同一 id（outbox 载荷里读出 reservationId 断言 distinct==1）。
  - `theDatabaseRefusesASecondReservationRowForTheSameOrder`：raw JDBC 直插两行同
    `(tenant, order)`，修复前第二行照插（红），修复后 `DataIntegrityViolationException`。
- **在案边界**：失败路径（`StockReservationFailed`）不写行，窗口外重复会重新决策——但那
  不泄漏资源（要么再失败要么此刻能预留），与本 issue 的资源泄漏失败模式不同类，维持现状。

验证：`StockReservationAtomicityTest` 6/6，scaffold 全量验收 BUILD SUCCESS（start 92/0/0）。

## 关联

- 传输级去重的另一缺口:[[issue-00129-in-process-redelivery-was-not-deduplicated]]
- 唯一约束属于 schema 兜底同类：[[issue-00146-the-flagship-invariants-have-no-last-line]]
