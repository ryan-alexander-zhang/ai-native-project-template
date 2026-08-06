---
id: issue-00148-eight-internal-commands-skip-the-promised-validation
type: issue
status: resolved
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

2026-07-31 修复。两半都做了：补约束（修当下），加 ArchUnit 规则（修根因——"校验覆盖靠
个人自觉"从此变成机制）。

- **裸奔清单已涨到 10 个**：评审点名的 8 个之外，issue-00144 修复期间新加的
  `RequestPaymentVoid`（ordering）和 `VoidPayment`（payment）同样一字未标——在"补约束"
  与"加规则"之间的两周里回渗了两个，恰好实证根因。全部统一补齐：`String` 一律
  `@NotBlank`，`CancelOrder.reason` 补 `@NotNull`（`Order.cancel` 的运行时判空从兜底降回
  纵深防御）。
- **行为测试**：`CancelOrderBusValidationTest`（ordering-application，镜像
  `PlaceOrderBusValidationTest` 的手工装配真 bus + 真 `ValidationCommandInterceptor`）——
  修复前红：`CancelOrder(null, null)` 无一物拦截、直达 handler；修复后
  `ConstraintViolationException` 在门口拒绝且 handler 未运行。
- **机制**：库 `CqrsRules.commandComponentsShouldDeclareValidationConstraints()`（opt-in，
  不进 `all()`——它预设项目用 Bean Validation）：Command 实现的每个引用型 record 组件必须
  带一个 Bean Validation 声明（`@Constraint` 元注解的约束，或级联的 `@Valid`）。要的是
  **声明**而非全员 `@NotNull`：刻意可空的组件用容 null 约束（`@Size`/`@Positive`/`@Pattern`
  对 null 全部放行）把可选性写成字，与"漏标"从此可区分；基本类型组件跳过（非空天然成立）。
  库侧 fixture 测试 good 过 / bad 抛；scaffold `ArchitectureTest` 挂上该规则——修复前红
  **一次点名全部 15 个裸组件**（含两个回渗的），修复后 7 条规则全绿。

验证：库 fixture 测试 + scaffold 全量验收 `clean test -pl start -am` BUILD SUCCESS
（`CancelOrderBusValidationTest` 2/2，`ArchitectureTest` 7/7）。
