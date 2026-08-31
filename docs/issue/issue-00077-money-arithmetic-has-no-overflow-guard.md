---
id: issue-00077-money-arithmetic-has-no-overflow-guard
type: issue
status: resolved
blocks: [report-00002-scaffold-ddd-review]
---

# Money 的加法与乘法没有溢出保护：一个演示金额建模的值对象会静默回绕

## 问题（现状，file:line 为证）

- **等级：Low（触发需要极端输入；但 `Money` 是本项目唯一的金额值对象，也是读者学"值对象怎么写"的样板）**。
- `Money` 用 `long` 存最小货币单位，两处算术都是裸运算（`Money.java:23-33`）：

```java
public Money plus(Money other)  { ...; return new Money(amountMinor + other.amountMinor, currency); }
public Money times(int factor)  { ...; return new Money(amountMinor * factor, currency); }
```

- 构造器只拒绝负数（`Money.java:11-13`），所以：
  - 溢出成负数 → 构造器抛 `DomainException("amount must be >= 0")`，**报错信息与真实原因无关**
    （调用方看到的是"金额不能为负"，而实际是溢出）；
  - 溢出回绕成正数 → **静默得到一个错误的金额**，没有任何提示。
- 上游放大了可达性：`OrderLine` 只校验 `quantity > 0`（`OrderLine.java:23-25`），
  **没有上界**；`PlaceOrder.Line.quantity` 是 `@Positive int`（`PlaceOrder.java:40`），
  上界即 `Integer.MAX_VALUE`。`unitPrice.times(quantity)`（`OrderLine.java:43-45`）
  因此可以是 `long × 21亿`。
- `Order.total()` 又对最多 100 行做 `reduce(Money::plus)`（`Order.java:182-187`），
  是第二个溢出面。
- `MoneyTest` 覆盖了负数、零、币种不匹配、负因子，**唯独没有溢出用例**
  （`MoneyTest.java:14-70`），所以 PIT 的 90% 变异阈值也不会发现这个缺口。

## 根因（第一性）

1. **观察 vs 期望**：期望"金额运算要么给出正确结果，要么明确失败"；
   实际"存在一个输入区间，它给出错误结果且不失败"。
2. **最小机制**：Java 的 `+` 与 `*` 对 `long` 是**回绕**语义，不抛异常。
   值对象把不变量放在构造器里，而构造器只看**结果**，看不到"这个结果是回绕来的"。
3. **真根因**：`Money` 的不变量被定义成"金额非负"，而它真正需要的是
   "**金额落在可表示区间内**"。前者是后者的一个投影，且是丢失信息的那个投影——
   回绕成正数的情况完全落在"非负"里。
4. **排除的伪根因**：不是选 `long`（而非 `BigDecimal`）的问题——
   最小货币单位 + `long` 是正确且高效的建模，Stripe 等都这么做；
   问题只是没有用会失败的算术。

## 复现（test-first）

```java
@Test
void additionRefusesToOverflowInsteadOfWrappingAround() {
  Money huge = Money.of(Long.MAX_VALUE, "USD");
  assertThrows(DomainException.class, () -> huge.plus(Money.of(1, "USD")));
}

@Test
void multiplicationRefusesToOverflowInsteadOfWrappingAround() {
  Money m = Money.of(Long.MAX_VALUE / 2, "USD");
  assertThrows(DomainException.class, () -> m.times(4));
}

@Test
void anOrderLineQuantityHasAnUpperBound() {
  assertThrows(DomainException.class,
      () -> Order.place(ID, CUST, List.of(new LineData("SKU-1", Integer.MAX_VALUE,
          Money.of(1_000_000, "USD"))), ReviewRequirement.notRequired()));
}
```

前两条当前都不抛（回绕后为负 → 抛的是 `"amount must be >= 0"`，
`assertThrows(DomainException.class)` 会**误判为通过**——所以断言必须同时检查消息或错误码，
否则这个测试自己就是假绿）：

```java
DomainException ex = assertThrows(DomainException.class, () -> huge.plus(Money.of(1, "USD")));
assertTrue(ex.getMessage().contains("overflow"), () -> "误判为负数校验：" + ex.getMessage());
```

## 修复

```java
public Money plus(Money other) {
  requireSameCurrency(other);
  return new Money(exact(() -> Math.addExact(amountMinor, other.amountMinor)), currency);
}

public Money times(int factor) {
  if (factor < 0) throw new DomainException("factor must be >= 0");
  return new Money(exact(() -> Math.multiplyExact(amountMinor, (long) factor)), currency);
}

private static long exact(LongSupplier op) {
  try { return op.getAsLong(); }
  catch (ArithmeticException e) { throw new DomainException("monetary amount overflow"); }
}
```

并给 `OrderLine.quantity` 加一个业务上界（例如 `MAX_QUANTITY = 10_000`），
与已有的 `Order.MAX_LINES = 100`（`Order.java:43`）对称——
一个订单行能有多少件本来就是业务问题，不该由 `int` 的宽度回答。

建议同时给 `Money` 加一个 `ErrorCode`（如 `OrderingErrorCode.AMOUNT_OVERFLOW`）：
目前这些 `DomainException` 都不带错误码（`Money.java:12,15,30,42`），
到了 API 边界只能落进 `about:blank` 家族。

## 验证结果

已修，按修复方案原样落地。

- `Money.plus` / `times` 改用 `Math.addExact` / `Math.multiplyExact`，
  经 `exact(LongSupplier)` 把 `ArithmeticException` 转成带码的 `DomainException`。
- 新增 `OrderingErrorCode.AMOUNT_OVERFLOW`（建议项，已采纳）与 `QUANTITY_OUT_OF_RANGE`；
  后者也补给了 `OrderLine` 原来无码的 `quantity must be > 0`。
- `OrderLine.MAX_QUANTITY = 10_000`，与 `Order.MAX_LINES = 100` 对称。
  注释写明理由：一行能有多少件是业务问题，交给 `int` 的宽度回答等于选了 2,147,483,647。
- `Money.minus`（issue-00071 时新增）本来就拒绝为负，无需改动。
- javadoc 记下了本 issue 根因第 3 条：真正的不变量是"金额可表示"，
  而"金额非负"是它的**有损投影**——回绕成正数的情况完整地落在"非负"里。

**本 issue 关于测试的警告是对的，而且实测验证了**：负向对照（把两处改回裸运算）中，
`aMultiplicationThatWouldWrapToAPositiveNumberIsRefused` 与另两条一起红，其中一条的输出正是

```
must be reported as overflow, not as a negative amount: amount must be >= 0
```

——如果只写 `assertThrows(DomainException.class, ...)`，这条测试会**假绿**。
所以三条断言全部检查 `errorCode()` 而不是异常类型。`MoneyTest` 从 10 条增至 14 条。

## 关联

- [report-00002-scaffold-ddd-review](../report/report-00002-scaffold-ddd-review.md)
- [design-00003-exception-model](../design/design-00003-exception-model.md)（无错误码的 `DomainException` 在边界上的呈现）
