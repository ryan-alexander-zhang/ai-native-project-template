---
id: issue-00150-a-dozen-small-inconsistencies-from-the-2026-07-30-review
type: issue
status: resolved
---

# 2026-07-30 评审的零星一致性项（伞形清单）

单独立 issue 太碎、又不该丢的 12 项。每项独立可做，做完划掉；若某项膨胀，拆成独立 issue。

## 模型与注解

- [x] **`OrderLine` 标注 `@Entity` 但没有身份**（`OrderLine.java:14-15`）：无 id、无
  `equals/hashCode`、字段全 final、持久化整组重写——事实上是值对象。改 `@ValueObject`
  （可顺手 record 化），与"整组替换"语义一致。
- [x] **inventory 聚合零领域事件，与 ordering 不对称且无解释**：`Stock`/`Reservation` 全程
  不 `registerEvent`，成功事实由 handler 直接组装集成事件。无内部订阅者时不算错，但两个
  BC 演示了两种矛盾答案。至少在 `package-info` 写明理由，或补标准 drain 路径示范一致性。
- [x] **`Order.place` 接收裁决而非政策**（`Order.java:79-108`）：传
  `ReviewRequirement.notRequired()` 即绕过审查，强制力在应用层惯例里。可改双分派
  `place(..., ManualReviewPolicy)`，或在 javadoc 声明当前取舍（可测性优先）。
- [x] **`Stock.reserve` 的 `quantity <= 0` 抛无码异常**（`Stock.java:38-40`），而
  `InventoryErrorCode.INSUFFICIENT_STOCK` javadoc 声称覆盖该情形——文档与代码必居其一。
- [x] **`AbstractAggregateRoot.equals` 对两个 id 为 null 的新聚合互等**（:144-152）：
  `id() == null` 时应回退 `this == other`，一行。
- [x] **`versionAdvanced()` public 可被业务代码误调**（:133-135）：至少加 ArchUnit 规则
  禁止 application/domain 层调用。
- [x] **`Association` 是全仓库零使用的死抽象**（`core/model/Association.java`）：scaffold 用
  裸 ID 引用（正确且更简单）。删掉，或让脚手架示范一次它的增量价值。
- [x] **`Identifier` 宣称防身份混用但无约束**：`AbstractAggregateRoot<ID>` 不要求
  `ID extends Identifier`。趁 pre-production 加泛型约束，或降级措辞。

## CQRS 与流程

- [x] **`FindOrderHandler` 用写侧仓储回答查询**（`FindOrderHandler.java:32`）与
  `Query.java:5-6` 的 "never goes through the write repositories" 冲突。二选一：改走
  `OrderQueries.byId`（`cancellableByCustomer` 用 status 列可算），或把框架措辞降为"默认"。
- [x] **一处终态不取消仍在计时的 deadline**（`OrderFulfilmentDefinition.java:336-346`，
  `AWAITING_STOCK_ORDER_CANCELLED` 收 `StockReservationFailed` 的 `completed` 不带
  `CancelDeadline(STOCK_DEADLINE)`；同步骤 `StockReserved` 分支 :333 就正确地带了）。一行。
- [x] **发布语言里的"祈使句"未标记**（`PaymentRequested`、`StockReleaseRequested`）：命令
  伪装的事件与事实事件同居 api、无契约级区分；当前对端幂等齐备所以正确，但后来者给
  request 加消费者时容易漏建幂等。javadoc 统一模板标注 "request, consumer must dedupe by X"。
- [x] **同步可用性闸口不看数量**（`StockQuery` 只有 skus；`StockAvailabilityService.java:39`
  按 `available > 0`）：999 件的单同步放行后注定走整圈补偿。契约加 `(sku, quantity)` 是
  现成的 additive 版本演进示范。

## 生产刚需的可选组件（框架增强）

- [x] **乐观锁冲突有界自动重试拦截器**：`ConcurrencyTranslationCommandInterceptor` 只翻译
  不重试，纯 lost-race 一律 409 到客户端。提供 opt-in 的
  `RetryOnConflictCommandInterceptor`（order 50–100 之间，指数退避 + 上限）。
- [x] **QueryBus 可选拦截链**：读侧日志/授权/慢查询观测无框架挂点，与"CommandBus 是治理
  单点"的卖点不对称。

## 部署清单项（已自我声明的债，防遗漏）

- [x] **`/ops` 无鉴权 + 租户豁免**（`DeadLetterOpsController.java:30-33`、
  `application.yml:213-220`）：注释已声明"真实部署放操作员角色后面"，上线 checklist 必项。

## 验证结果

2026-07-31 全部 15 项完成，分两个 commit：库侧 `f090856`，scaffold 侧为携带本文件的同一 commit（`fix(scaffold): settle the review's nine scaffold-side loose ends`）。逐项裁决：

**模型与注解**（库 6 + scaffold 2）：

1. `OrderLine` → `@ValueObject` **record**（组件访问器同名零波及；守卫进紧凑构造器）。
2. inventory 零领域事件 → **package-info 写明立场**：无域内订阅者时中转一跳无人受益；何时
   收敛（长出内部反应时走标准 drain 路径）也写了。
3. `Order.place` 收裁决 → **javadoc 声明取舍**：双分派并不真正堵洞（能伪造裁决的调用方同样
   能传恒准政策），值形式保住工厂的确定性与可测性；政策在 handler 消费一次。
4. `Stock.reserve` 无码 vs `INSUFFICIENT_STOCK` javadoc → **修文档**：非正数量是畸形请求
   （总线 @Positive 拦，域内守卫是无码兜底、按 issue-00131 浮出 unspecified），不是"库存
   不足"。
5. `AbstractAggregateRoot.equals` **null-id 回退 `this == other`**（红先行：两个未赋 id 的
   新聚合此前互等，Set 会静默合并）。
6. `versionAdvanced()` → 新 ArchUnit 规则
   `BuildingBlockRules.versionWitnessIsAdvancedOnlyByPersistenceAdapters()` **进 `all()`**
   （domain/application 调它 = 空转见证、拆掉乐观锁）；good/bad fixture + javadoc 点名规则。
7. `Association` **删除**（全仓零使用；package-info 记下"名字没有工作"的裁决）。
8. `Identifier` → **`AbstractAggregateRoot<ID extends Identifier>` 泛型约束**，承诺由编译器
   兑现。波及 8 个测试 fixture（全部 String id）逐个换 record id；scaffold 四个聚合 id 本就
   实现 Identifier，零改动。

**CQRS 与流程**（4）：

9. `FindOrderHandler` vs `Query` javadoc → **框架措辞从 never 降为 by default**（issue 给的
   选项 b）：单实体读形状随聚合时可走写仓储，永不越界的是"变更"；handler javadoc 反向
   引用这条被点名的例外。不复制 `cancellableByCustomer` 进读模型——规范一处定义。
10. `AWAITING_STOCK_ORDER_CANCELLED` 收 `StockReservationFailed` 的 completed **补
    `CancelDeadline(STOCK_DEADLINE)`**（负向对照实跑：回退后测试红 expected 1 was 0）。
    TimedOut 分支刻意不取消——"this decision *is* the deadline"，与全表惯例一致。
11. 三个请求事件（`PaymentRequested`/`StockReleaseRequested`/`PaymentVoidRequested`——第三个
    是 00144 后新增的，清单只点了两个）加**统一 "A request, not a fact" javadoc 段**：消费方
    必须按哪个键幂等、现有消费方如何做到。
12. `StockQuery` 加数量：`Line(sku, quantity>=1)`，服务侧**同 SKU 求和后**与库存比较（两行
    各 3 对库存 5：单独都过、合计不过），Report 语义改为"要的数量给不给得起"；ordering
    端口/预检/适配器全链带量。`StockAvailabilityServiceTest` 钉 999 对 5 同步拒绝。

**框架增强**（2）：

13. **`RetryOnConflictCommandInterceptor`**（ORDER=75：在并发翻译 50 之内、校验 100 之外，
    每次重试都是全新 dispatch——新事务、重新加载、预检重跑）：指数退避 + 硬上限，耗尽原样
    重抛（409 路径是后备不是牺牲品）；**opt-in**
    （`aipersimmon.ddd.cqrs.retry-on-conflict.enabled`）——"handler 无事务外副作用"是应用的
    性质，只能由部署断言；中断即停。7 条单测（含中断、穿透非冲突异常、order 区间）。
14. **QueryBus 拦截链**：`QueryInterceptor`（比命令侧刻意窄：无 context——查询不铸消息身份）
    + `RegistryQueryBus` 排序折叠链 + auto-config 收集 bean；**框架不内置任何拦截器**，零注册
    时行为逐字节不变。3 条单测（排序环绕、短路、无链不变）。

**部署清单**（1）：

15. `/ops` 无鉴权 + 租户豁免 → scaffold README 新增 **"Before production: the declared
    debts"** 清单节（/ops 放操作员角色后 + in-memory fallback 拒绝要保留——后者核实了真实
    键名 `allow-in-memory-stores` 与所在文件后才写）。

另一个只有全量验收才暴露的交互：`OrderingFlowTest` 的"库存不足走异步补偿"测试建立在
SKU-only 闸口的盲区上（999 对 10 同步放行），第 12 项堵住盲区后该测试被预检当场拒绝。
改写为制造异步路径真正为之存在的窗口——下单后、异步预留前 raw 抽干库存（outbox relay
200ms 轮询窗稳赢），advisory 闸口对未来无能为力正是保留补偿路径的理由。

验证：库全 reactor `clean install` BUILD SUCCESS ×2（六项后 + Query 措辞后）；scaffold
全量验收 `clean test -pl start -am` BUILD SUCCESS（start 100/0/0）。
