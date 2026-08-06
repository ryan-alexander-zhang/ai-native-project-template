---
id: issue-00145-aggregates-trust-their-callers-at-the-door
type: issue
status: resolved
---

# 几个聚合在门口信任了调用方：构造/重建守卫批量补齐

2026-07-30 全面评审（P2 批量项）。共同模式：现有调用路径恰好都传了合法值，所以全是潜伏
缺陷——错误会在离成因很远的地方以无关消息爆出。

## 清单

1. **`Reservation` 不校验持有数量为正**（`inventory-domain/.../Reservation.java:39-50`）：
   `Map<Sku, Integer>` 的 value 可为 0 或负数；持有 `-5` 件的 Reservation 能被创建持久化，
   错误直到释放时才在另一个聚合 `Stock.release`（`Stock.java:53`）以 "quantity must be > 0"
   爆出——离成因两个事务之遥。改：构造器逐项校验，或引入自校验的
   `record HeldLine(Sku sku, int quantity)` 替代 Map（顺带消除 `held()` 返回
   `List<Map.Entry<Sku,Integer>>` 这种非领域语言）。
2. **`OrderLine` 不校验 `unitPrice` 非空**（`ordering-domain/.../OrderLine.java:31-50`）：
   sku、quantity 都有守卫，唯独 unitPrice 直接赋值，NPE 延迟到 `subtotal()`（:65）。
3. **订单"单一货币"不变量只被算术副作用兜住**：混合货币订单在 `Order.place` 经 `total()`
   （`Order.java:103, 206-211`）归约时由 `Money.requireSameCurrency`（`Money.java:79-83`）
   抛无码 "currency mismatch"。规则存在但没有名字、没有码、不在聚合的不变量清单里。改：
   仿 `OrderHasDistinctSkus` 做成 `Invariant` + 专属码（如 `ordering.mixed-currency`）。
4. **`Customer` 构造/重建不设防**（`ordering-domain/.../Customer.java:44-69`）：不校验
   `id`/`creditLimit` null、不校验 `usedCredit` 与 `creditLimit` 同货币；坏行数据 rehydrate
   后在 `reserveCredit`（:80-96）远处爆出；`reserveCredit(null)` 亦 NPE。
   （`used <= limit` 可放宽——历史数据可能合法超限——但至少文档化。）
5. **`Stock`/`Reservation` 不校验身份非空**（`Stock.java:15-21`：sku 是身份，null 会进
   `equals/hashCode`；`Reservation.java:39`：id 同）。
6. **`Money.currency` 是任意非空字符串**（`Money.java:9-18`）：`"usd"` 与 `"USD"` 是两种
   货币，`"XYZ"` 也合法。改：构造时 `Currency.getInstance` 校验或至少规范化大小写 + 三字母
   模式。
7. **`Reservation.orderId` 是裸 String**（`Reservation.java:21, 76`）：上下文内唯一退回原始
   类型的 id。改：本地 `record OrderRef(String value)`——不 import ordering 类型，也不与
   任意字符串混淆。
8. **payment 领域端口 primitive obsession**（`payment-domain/AuthorizationPolicy.java`：
   `decide(long amountMinor, String currency)`）："published contract 应扁平"适用于 api
   集成事件，不适用于领域端口本身。改：payment 自己的 `Amount` VO。

## 复现（先写失败测试）

每项一条构造期断言测试（如 `new Reservation(id, order, Map.of(sku, -5))` 断言抛出），
修复前全部安静通过。

## 验证结果

2026-07-31 修复，八项全做，每项构造期断言测试先红（1/2/4/5/6 行为红——"nothing was
thrown"；3/7/8 引入新类型/签名 = 编译红）：

1. **Reservation 持有数量**：构造器逐项校验 `> 0`（含 null value）。**没有做 HeldLine 重构**
   （在案）：缺陷本体是守卫缺失，`held()` 的 `List<Map.Entry>` 是风格问题，不值得为它
   连带改写 handler 与两个仓储。顺带补了 **null id 守卫**（原清单第 5 项的 Reservation 半边）。
2. **OrderLine.unitPrice** 补非空守卫（此前 NPE 延迟到 `subtotal()`）。
3. **单一货币不变量**：新 `OrderHasSingleCurrency`（镜像 `OrderHasDistinctSkus`）+
   `OrderingErrorCode.MIXED_CURRENCY("ordering.mixed-currency")`，`Order.place` 在
   `total()` 之前 `checkInvariant`——规则从算术副作用（`Money.requireSameCurrency` 的无码
   "currency mismatch"）升格为有名字有码的聚合不变量。
4. **Customer**：私有主构造器成为创建与 rehydrate 共用的门（id/creditLimit/usedCredit 非空
   + 双 Money 同货币）；公共构造器在解引用 `creditLimit.currency()` **之前**先拒绝 null
   （首轮实现在这里被测试抓出 NPE 而非 DomainException）；`reserveCredit(null)` 拒绝。
   **`used <= limit` 刻意不守卫并写进 javadoc**：调低额度合法地把存量债务滞留在新额度之上
   ——这样的行能 release 不能 reserve，正是对的行为，拒绝加载等于拒绝合法历史。
5. **身份非空**：`Stock` 拒绝 null sku（身份进 equals/hashCode 前拦下）；Reservation 见第 1 项。
6. **Money.currency 过 ISO 4217**（`Currency.getInstance`，拒绝 "usd"/"XYZ"/"US"）。**校验
   不归一化**（在案）：大小写不同的调用方是个该浮出的 bug，静默吸收更糟。仓内字面量已核实
   全为合法 ISO（USD×63、EUR×2）。
7. **`OrderRef`**（inventory 本地 VO，null/blank 拒绝）：Reservation 构造器/`orderId()`/
   `reconstitute`、`Reservations.findByOrderId`（00147 刚加的那个，一并升格）、两个 handler、
   `MyBatisReservations` 全链换型。javadoc 写明双向立场：不 import ordering 的 OrderId
   （DDL 无跨界外键 + ArchitectureTest 拦），也不再退回裸 String。
8. **payment `Amount` VO**（minor >= 0——零合法、issue-00075 的立场保留；currency 过 ISO）：
   `AuthorizationPolicy.decide(Amount)`、`CeilingAuthorizationPolicy`、
   `AuthorizePaymentHandler` 换签名；javadoc 点明"published contract 应扁平"只约束 api
   事件、不约束领域端口自身。

测试：`ReservationTest`/`StockTest`/`InventoryValueObjectsTest`/`OrderLineAndInvariantTest`/
`CustomerTest`/`MoneyTest`/`AmountTest`/`AuthorizationPolicyTest` 增改。验证：六个受牵连
模块全绿 + scaffold 全量验收 `clean test -pl start -am` BUILD SUCCESS。
