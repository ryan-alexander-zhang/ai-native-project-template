---
id: issue-00150-a-dozen-small-inconsistencies-from-the-2026-07-30-review
type: issue
role: main
status: open
---

# 2026-07-30 评审的零星一致性项（伞形清单）

单独立 issue 太碎、又不该丢的 12 项。每项独立可做，做完划掉；若某项膨胀，拆成独立 issue。

## 模型与注解

- [ ] **`OrderLine` 标注 `@Entity` 但没有身份**（`OrderLine.java:14-15`）：无 id、无
  `equals/hashCode`、字段全 final、持久化整组重写——事实上是值对象。改 `@ValueObject`
  （可顺手 record 化），与"整组替换"语义一致。
- [ ] **inventory 聚合零领域事件，与 ordering 不对称且无解释**：`Stock`/`Reservation` 全程
  不 `registerEvent`，成功事实由 handler 直接组装集成事件。无内部订阅者时不算错，但两个
  BC 演示了两种矛盾答案。至少在 `package-info` 写明理由，或补标准 drain 路径示范一致性。
- [ ] **`Order.place` 接收裁决而非政策**（`Order.java:79-108`）：传
  `ReviewRequirement.notRequired()` 即绕过审查，强制力在应用层惯例里。可改双分派
  `place(..., ManualReviewPolicy)`，或在 javadoc 声明当前取舍（可测性优先）。
- [ ] **`Stock.reserve` 的 `quantity <= 0` 抛无码异常**（`Stock.java:38-40`），而
  `InventoryErrorCode.INSUFFICIENT_STOCK` javadoc 声称覆盖该情形——文档与代码必居其一。
- [ ] **`AbstractAggregateRoot.equals` 对两个 id 为 null 的新聚合互等**（:144-152）：
  `id() == null` 时应回退 `this == other`，一行。
- [ ] **`versionAdvanced()` public 可被业务代码误调**（:133-135）：至少加 ArchUnit 规则
  禁止 application/domain 层调用。
- [ ] **`Association` 是全仓库零使用的死抽象**（`core/model/Association.java`）：scaffold 用
  裸 ID 引用（正确且更简单）。删掉，或让脚手架示范一次它的增量价值。
- [ ] **`Identifier` 宣称防身份混用但无约束**：`AbstractAggregateRoot<ID>` 不要求
  `ID extends Identifier`。趁 pre-production 加泛型约束，或降级措辞。

## CQRS 与流程

- [ ] **`FindOrderHandler` 用写侧仓储回答查询**（`FindOrderHandler.java:32`）与
  `Query.java:5-6` 的 "never goes through the write repositories" 冲突。二选一：改走
  `OrderQueries.byId`（`cancellableByCustomer` 用 status 列可算），或把框架措辞降为"默认"。
- [ ] **一处终态不取消仍在计时的 deadline**（`OrderFulfilmentDefinition.java:336-346`，
  `AWAITING_STOCK_ORDER_CANCELLED` 收 `StockReservationFailed` 的 `completed` 不带
  `CancelDeadline(STOCK_DEADLINE)`；同步骤 `StockReserved` 分支 :333 就正确地带了）。一行。
- [ ] **发布语言里的"祈使句"未标记**（`PaymentRequested`、`StockReleaseRequested`）：命令
  伪装的事件与事实事件同居 api、无契约级区分；当前对端幂等齐备所以正确，但后来者给
  request 加消费者时容易漏建幂等。javadoc 统一模板标注 "request, consumer must dedupe by X"。
- [ ] **同步可用性闸口不看数量**（`StockQuery` 只有 skus；`StockAvailabilityService.java:39`
  按 `available > 0`）：999 件的单同步放行后注定走整圈补偿。契约加 `(sku, quantity)` 是
  现成的 additive 版本演进示范。

## 生产刚需的可选组件（框架增强）

- [ ] **乐观锁冲突有界自动重试拦截器**：`ConcurrencyTranslationCommandInterceptor` 只翻译
  不重试，纯 lost-race 一律 409 到客户端。提供 opt-in 的
  `RetryOnConflictCommandInterceptor`（order 50–100 之间，指数退避 + 上限）。
- [ ] **QueryBus 可选拦截链**：读侧日志/授权/慢查询观测无框架挂点，与"CommandBus 是治理
  单点"的卖点不对称。

## 部署清单项（已自我声明的债，防遗漏）

- [ ] **`/ops` 无鉴权 + 租户豁免**（`DeadLetterOpsController.java:30-33`、
  `application.yml:213-220`）：注释已声明"真实部署放操作员角色后面"，上线 checklist 必项。

## 验证结果

未修复。逐项完成时在本文件划掉并注明 commit。
