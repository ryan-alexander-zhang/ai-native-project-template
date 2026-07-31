---
id: issue-00145-aggregates-trust-their-callers-at-the-door
type: issue
role: main
status: open
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

未修复。
