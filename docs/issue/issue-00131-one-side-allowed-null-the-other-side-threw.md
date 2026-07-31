---
id: issue-00131-one-side-allowed-null-the-other-side-threw
type: issue
role: main
status: open
---

# 失败码一侧允许为空，另一侧对空抛异常——两边各自"正确"，合起来是毒丸

2026-07-30 全面评审（P0）。

## 问题

`StockReservationFailed.code` 的可空性在契约两侧写反了：

- **生产侧**：`inventory-application/.../ReserveStockHandler.java:113` —
  `String code = failure.errorCode().map(ErrorCode::code).orElse(null);`，注释明写
  "the failing code (if any)"，契约 record 不保证非空。
- **消费侧**：`OrderFulfilmentDefinition.java:297-299` 把该 code 原样塞进
  `ReservationFailureRef`，其构造器（`ordering-domain/.../ReservationFailureRef.java:22`）
  对 null/blank `reasonCode` 抛 `DomainException`。

## 根因（第一性）

- 期望：inventory 的每一个 `StockReservationFailed` 都能被 ordering 的流程消化。
- 分歧机制：inventory 侧抛出一个不带 `ErrorCode` 的 `DomainException` 是被类型允许的——
  `Stock.java:38-39` 的 `quantity <= 0` 分支就是现成的无码抛出（目前仅被 Bean Validation
  挡在门外）。一旦发生：`react()` 在消费事务里抛异常 → 输入变毒丸 → 流程卡在
  `AWAITING_STOCK` 直到 STOCK deadline 以 `STOCK_TIMEOUT` 收场——错误归因还错了
  （明明 inventory 答复了）。
- 真根因：发布语言字段的可空性没有写进契约本身，两侧各按自己的局部合理性做了相反假设。

## 复现（先写失败测试）

向流程投一条 `code = null` 的 `StockReservationFailed`（走 `OrderFulfilment` adapter 翻译
路径），断言流程以 `stock-failed` 类目正常终止而非在 react 中抛出。修复前该测试以
`DomainException` 失败。

## 改法

首选生产侧兜底：`orElse("inventory.unspecified")`，并把"非空"写进 `StockReservationFailed`
的 javadoc 成为契约保证。次选消费侧 ACL 翻译 null 为哨兵码。顺手项：
`OrderFulfilmentCodecs.encodeCancel` 里 `String.join` 遇 null 元素写出 `"null"` 字面量的
次生问题随 [[issue-00136-the-second-process-pays-the-boilerplate-again]] 的 codec 删除一并消失。

## 验证结果

未修复。

## 关联

- payload 校验的系统性缺口：[[issue-00143-the-headers-are-checked-and-the-payload-is-trusted]]
