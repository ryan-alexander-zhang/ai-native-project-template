---
id: issue-00068-stock-waits-have-no-deadline-and-can-park-forever
type: issue
status: resolved
blocks: [report-00002-scaffold-ddd-review]
---

# 只有 payment 装了 deadline：等库存与等释放同样是外部作答，却可以永久停住

## 问题（现状，file:line 为证）

- **等级：High（订单永久卡在 `FULFILMENT_IN_PROGRESS`，客户已过自助取消窗口，没有任何端点能救它；且这是一条无告警的静默停滞）**。
- `OrderFulfilmentDefinition` 只在**一个** step 上装了定时器：`onAwaitingStock` 收到 `StockReserved` 时
  `new ScheduleDeadline(PAYMENT_DEADLINE, ...)`（`OrderFulfilmentDefinition.java:182-185`）。
- 依据写在类注释里（`OrderFulfilmentDefinition.java:55-56`）与 `README.md:56`：

  > *"Payment is the only step whose answer comes from outside and may simply never arrive, so it is the only step with a timer."*

  这句话**不成立**。`AWAITING_STOCK` 等的是 inventory 上下文经 Kafka 回的
  `StockReserved` / `StockReservationFailed`；`AWAITING_STOCK_RELEASE` 等的是 `StockReleased`。
  两者与 payment 一样是**跨上下文、跨 broker 的外部作答**。
- 这两个 step 上，回答"永不到达"是可达的，不是理论风险：
  - `ReserveStockHandler` 只 catch `DomainException`（`ReserveStockHandler.java:90`）。
    **`OptimisticLockingFailureException` 不是 `DomainException`**——两个订单并发预留同一 SKU 时它必然出现
    （这正是 `ConcurrentAggregateWriteTest` 证明的机制），命令抛出 → 不发任何事件。
  - 同样逃出 catch 的还有：命令总线的 Bean Validation 失败（`ReserveStock` 带非法 payload）、DB 故障。
  - `ReleaseStockHandler` 的 `RESERVATION_NOT_FOUND` 直接 `orElseThrow`（`ReleaseStockHandler.java:43-47`），
    同样不发 `StockReleased`。
- inbox 重试通常能自愈（事务回滚 ⇒ inbox 未记录 ⇒ 重投递时重读快照多半成功）。
  但**重试预算耗尽 → 进死信之后，就没有任何机制会再推动这个流程实例**：
  没有 deadline、没有 `max-lifetime`（README 承认未启用，`README.md:164-171`）、没有运维端点可以强制推进或取消。
- `RuntimeOrderFulfilmentProcess.handle`（`:108-116`）对找不到实例的情况会 `IllegalStateException` 失败得很响，
  但对**实例存在却没人再喂事实**的情况，没有任何对应物。

## 根因（第一性）

1. **观察 vs 期望**：期望"任何一个等外部作答的 step 都能停止等待"；
   实际"只有被识别为'外部作答'的那一个 step 能停止等待"。
2. **最小机制**：`ScheduleDeadline` 只在 `onAwaitingStock` 的 `StockReserved` 分支被构造
   （`OrderFulfilmentDefinition.java:182`），全文件仅此一处。其余三个 `AWAITING_*` step 的
   非预期输入一律走 `ignore`（`:202`、`:264`）——幂等地什么都不做，也就永远不会离开该 step。
3. **真根因**：判据用错了。用的是"**这个上下文会不会主动拒绝**"——payment 会静默（被 mock 掉就完全不答），
   inventory 会答 `StockReservationFailed`，所以看起来 inventory 总会答。
   正确判据是"**这个 step 的推进是否依赖一条可能永不到达的消息**"。
   inventory 只在**它自己判定为业务失败**（`DomainException`）时才答；技术失败下它一声不吭，
   这与 payment 静默在流程看来是同一件事。
   换言之：`ReserveStockHandler` 的 catch 块**范围**决定了"inventory 一定会答"这个假设是否成立，
   而流程定义把它当成了无条件成立的前提。
4. **排除的伪根因**：不是 deadline 机制不好用——README 的成本表把 deadlines 列为"none — this API fits"
   （`README.md:102`），API 本身是够的；缺的是对哪些 step 需要它的判断。
   也不是 `ignore` 写错了——乱序事实必须 `ignore`，这是对的；问题在于 `ignore` 之后没有任何兜底。

## 复现（test-first）

`PaymentTimeoutFlowTest` 已经给出了模板（把 `PaymentRequestedListener` 用 `@MockitoBean` 静默掉）。
对称地写 `StockReservationTimeoutFlowTest`：

```java
@MockitoBean OrderReadyForFulfilmentListener silentInventory;   // inventory 永不作答

@Test
void aStockReservationThatNeverAnswersDoesNotParkTheOrderForever() {
  String orderId = commandBus.send(new PlaceOrder("CUST-1", List.of(...)));
  // 当前：一直是 FULFILMENT_IN_PROGRESS，流程停在 AWAITING_STOCK —— 本用例会超时失败
  await().atMost(SETTLE).untilAsserted(() -> assertEquals("CANCELLED", status(orderId)));
}
```

第二条覆盖技术失败逃出 catch 的路径（这条更贴近真实）：

```java
@Test
void aReserveStockThatDeadLettersDoesNotParkTheOrderForever() {
  // 用一个 @Primary Stocks 装饰器，在 save 时抛 OptimisticLockingFailureException，
  // 直到重试预算耗尽、消息进死信；断言订单最终仍到达终态而不是永久 FULFILMENT_IN_PROGRESS。
}
```

## 修复

三选一，按代价从小到大：

1. **给 `AWAITING_STOCK` 与 `AWAITING_STOCK_RELEASE` 各装一个 deadline**（推荐）。
   与 payment 完全同构：`start` 分支 arm `STOCK_DEADLINE`，`onAwaitingStock` 的两个正常出口 cancel 它；
   `AWAITING_STOCK` 超时走 `StockReservationFailed(code = "STOCK_TIMEOUT")` 的既有补偿路径
   （**无需新补偿分支**——正如 payment 超时复用了 decline 的路径）。
   `AWAITING_STOCK_RELEASE` 超时则应 **suspend 供人工介入**，不能自动往下 cancel：
   那条路径要求 `StockReleaseRef` 作为证据，而超时恰恰意味着没有证据——
   `CancellationReason.PaymentDeclinedAfterStockReleased` 在类型上就构造不出来。这正是该设计的价值所在。
2. **启用 `instance.max-lifetime` 并在 `react` 里处理 `MaxLifetimeExceeded`**。
   `react` 已经预留了拒绝分支（`OrderFulfilmentDefinition.java:149-154`），改为 suspend 即可。
   粒度粗，但一次覆盖所有 step。
3. **至少把理由改对**：若决定不修，README 与类注释里"payment is the only step whose answer comes
   from outside"必须改写，否则它会把一个已知缺口描述成不存在的。

无论选哪条，都应同时收窄 `ReserveStockHandler` / `ReleaseStockHandler` 的失败语义
（见 [issue-00076-inventory-relies-on-an-upstream-invariant-to-protect-itself](issue-00076-inventory-relies-on-an-upstream-invariant-to-protect-itself.md)），
让"技术失败"与"业务失败"对流程呈现同一种可观测形态。

## 验证结果

已修。**采用修复方案 1**（给两个 step 各装 deadline），并连带做了方案 3（把理由改对）——
方案 3 本来是"若决定不修"的退路，但那句错误论断即使修了也必须改，所以两条一起做。

- 新增 `STOCK_DEADLINE`（`start` 时武装）与 `STOCK_RELEASE_DEADLINE`（进入 `AWAITING_STOCK_RELEASE`
  的两条路径都武装）；两个新输入 `StockReservationTimedOut` / `StockReleaseTimedOut`，
  连同 codec 注册（现在共三个计时器输入）。
- `AWAITING_STOCK` 超时**完全复用**既有补偿分支，如本 issue 所预判——无需新补偿路径。
  抽出 `cancelForInventory(...)`，refusal 与 silence 只差一个 code 和"是否还要取消计时器"。
  记录码 `STOCK_TIMEOUT`，证据 id 是计时器自己的投递。
- 超时时长可配：`ordering.fulfilment.stock-timeout`（默认 PT1M）与
  `ordering.fulfilment.stock-release-timeout`（默认 PT1M）。默认比 payment 的 PT2M 短，
  理由写在字段注释里：预留库存是我们自己运维的上下文里的本地决策，授权支付要等第三方。

**方案 1 关于 `AWAITING_STOCK_RELEASE` 的建议与运行时契约冲突，已改为另一种做法**：
本 issue 说超时应"suspend 供人工介入"，但 `ProcessLifecycle` 的 javadoc 明确写着
**"a `ProcessDefinition` must never return SUSPENDED"**——SUSPENDED 是运行时在投递/deadline
重试耗尽时自己设的运维状态，定义端无法请求它。

改为**再问一次**：超时后重发 `RequestStockRelease` 并重新武装计时器，留在原 step。
本 issue 对这一步的核心判断完全正确且是这么做的理由——
从这里取消需要 `StockReleaseRef`，而超时恰恰是这份证据的缺席，
`PaymentDeclinedAfterStockReleased` 在类型上就构造不出来。
所以这个等待**不能被结束，只能被满足**；放弃等于记录"库存已归还"而它并没有。
`ReleaseStock` 幂等（reservation 有 `released` 标志），deadline 按名字重排会顶替上一代，
所以既不会重复扣减也不会堆积计时器；inventory 一恢复，流程立刻正常走完。
刻意不设上限：一个持续在问的流程表现为长期 COMPENSATING 实例，这正是 process backlog 指标要看的；
要硬上限就该用 `instance.max-lifetime`（README「Known demo gaps」已相应更新）。

测试：
- 单元 6 条（`OrderFulfilmentDefinitionTest`，共 24 条全绿）：start 武装 STOCK deadline、
  两条正常出口都取消它、超时走 refusal 的同一分支且码为 STOCK_TIMEOUT、
  release 超时重发+重排且**不**取消、release 完成取消计时器、两条进入 release 的路径都武装计时器。
- 端到端 `StockReservationTimeoutFlowTest`：用 `@MockitoBean` 静默 `OrderReadyForFulfilmentListener`
  （与 `PaymentTimeoutFlowTest` 对称，正如本 issue 的模板所示），断言订单最终 CANCELLED、
  类别 INVENTORY_UNAVAILABLE、**库存一件没动**（不同于 payment 超时，这里没有要归还的东西）、
  流程实例 COMPLETED/ORDER_CANCELLED。
- 负向对照：拆掉 `start` 里的 ScheduleDeadline，该测试在 30 秒窗口内始终
  `expected: <CANCELLED> but was: <FULFILMENT_IN_PROGRESS>`——正是本 issue 描述的永久停滞。

**未做**：本 issue 复现段的第二条（用 `@Primary Stocks` 装饰器抛
`OptimisticLockingFailureException` 直到进死信）。技术失败逃出 catch 这条路径的**数据**后果
已由 [issue-00094-a-swallowed-domain-exception-leaks-stock-permanently](issue-00094-a-swallowed-domain-exception-leaks-stock-permanently.md) 修好（回滚、无部分扣减），
而"没人再喂事实"的后果正由本次的 STOCK deadline 兜住——两者叠加已覆盖该场景，
再造一个死信编排测试收益有限。
[issue-00076-inventory-relies-on-an-upstream-invariant-to-protect-itself](issue-00076-inventory-relies-on-an-upstream-invariant-to-protect-itself.md) 的失败语义收窄也已完成。

验证：`mvn -o verify -pl start -am` 全绿，71 个测试 0 失败，Spotless / PMD / SpotBugs 通过。

## 关联

- [report-00002-scaffold-ddd-review](../report/report-00002-scaffold-ddd-review.md)
- [design-00004-durable-process-manager-runtime](../design/design-00004-durable-process-manager-runtime.md)（deadline 与 max-lifetime 的契约）
- [issue-00076-inventory-relies-on-an-upstream-invariant-to-protect-itself](issue-00076-inventory-relies-on-an-upstream-invariant-to-protect-itself.md)（重复 SKU 是逃出 catch 的另一条路径）
- [issue-00070-ready-for-fulfilment-is-never-persisted](issue-00070-ready-for-fulfilment-is-never-persisted.md)（订单卡住时客户无法自助取消，与此叠加）
