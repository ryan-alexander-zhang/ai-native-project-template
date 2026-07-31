---
id: issue-00148-eight-internal-commands-skip-the-promised-validation
type: issue
role: main
status: open
---

# 总线承诺"为每个入口兜底"，八个内部命令却全数裸奔

2026-07-30 全面评审（P2）。

## 问题

ordering 的内部命令全部没有任何 Bean Validation 约束（逐个核实）：`CancelOrder`、
`ConfirmOrder`、`BeginFulfilment`、`RequestPayment`、`RequestStockRelease`、`ApproveReview`、
`RejectReview`、`ShipOrder`。带约束的只有 `PlaceOrder` 和 `CancelOwnOrder`（HTTP 入口的
两个）。`CancelOrder.reason` 连 `@NotNull` 都没有（靠 `Order.cancel:198-200` 的运行时判空
兜底）。

矛盾点：`ReserveStock.java:16-20` 的 javadoc 特意宣讲"命令来自事件监听器而非 HTTP，所以
约束**更**重要——总线为每个入口兜底"。同样来自 relay/监听器的 ordering 内部命令却没有
执行同一立场。

## 根因（第一性）

- 期望："哪些命令被校验"不需要逐个打开文件确认。
- 真根因：校验覆盖靠个人自觉，没有一条"每个 Command record 至少声明其非空契约"的机制或
  arch 规则。

## 复现（先写失败测试）

对 `CancelOrder(null, null)` 经总线派发，断言以 400 级校验错误被拒（而非深入 handler 后
以领域异常爆出）。修复前是后者。

## 改法

统一补 `@NotBlank`/`@NotNull`；可选：加一条 ArchUnit 规则（Command record 的引用型组件
必须带约束注解），把自觉变成机制。

## 验证结果

未修复。
