---
id: issue-00075-a-zero-amount-order-can-be-placed-but-not-paid
type: issue
status: resolved
parent: report-00002-scaffold-ddd-review
---

# 0 元订单可以下单但无法支付：跨上下文契约在"金额能否为零"上不一致

## 问题（现状，file:line 为证）

- **等级：Medium（用户可见的怪异行为：下单返回 201，两分钟后订单莫名其妙变成 CANCELLED，中间没有任何面向用户的解释）**。
- 三处对"金额可否为 0"的规定不一致：

  | 位置 | 约束 | 允许 0？ |
  |---|---|---|
  | `PlaceOrder.Line.unitAmountMinor` | `@PositiveOrZero`（`PlaceOrder.java:41`） | ✅ |
  | `PlaceOrderRequest.Line.unitAmountMinor` | `@PositiveOrZero`（`PlaceOrderRequest.java:41`） | ✅ |
  | `Money` | `amountMinor < 0` 才拒（`Money.java:11-13`） | ✅ |
  | `AuthorizePayment.amountMinor` | **`@Positive`**（`AuthorizePayment.java:32`） | ❌ |

- 于是这条路径是可达的：
  1. 客户下一张全部行 `unitAmountMinor = 0` 的订单 → 通过边缘校验、通过命令校验、
     通过 `Money` 构造、通过 `canAfford`（0 ≤ 任何额度）→ **201 Created**；
  2. 流程正常推进，库存预留成功；
  3. `RequestPaymentHandler` 发出 `PaymentRequested(amountMinor = 0)`（`RequestPaymentHandler.java:41-45`）；
  4. payment 侧 `PaymentRequestedListener` 把它变成 `AuthorizePayment` 命令，
     **命令总线的 Bean Validation 拒绝 `@Positive` 违例**，handler 从未执行；
  5. 异常抛回消费桥 → inbox 重试 → 每次都以同样方式失败 → 最终进死信；
  6. `PaymentAuthorized` / `PaymentDeclined` 都不会发出
     ⇒ 流程停在 `AWAITING_PAYMENT` ⇒ `PT2M` 后 deadline 触发 ⇒ 释放库存 → 取消订单。
- 用户拿到的是"下单成功 → 两分钟后被取消"，取消原因记录为 `PAYMENT_TIMEOUT`
  （`OrderFulfilmentDefinition.java:108`），**与真实原因（金额非法）毫无关系**。
- `ReserveStock` 侧没有这个问题（它不带金额），所以缺口只在 payment 这条边上。

## 根因（第一性）

1. **观察 vs 期望**：期望"能被接受的订单就能被支付"；
   实际"订单的金额值域比支付的金额值域宽，宽出来的那一格没有任何一侧拒绝它"。
2. **最小机制**：`PlaceOrder` 的 `@PositiveOrZero` 与 `AuthorizePayment` 的 `@Positive`
   是两个独立声明的约束，**没有任何机制要求它们相容**。
   一个是入口契约，一个是下游契约，中间隔着 outbox + Kafka + inbox，编译期与启动期都看不到彼此。
3. **真根因**：跨上下文的**值域契约**只存在于两侧各自的注解里，
   而 `ordering-api` 里的 `PaymentRequested`（发布语言，`PaymentRequested.java:18-20`）
   **没有为 `amountMinor` 声明任何约束**。发布语言本应是两侧共同承认的那份约定，
   它保持沉默，于是两侧各自猜了一个，猜得不一样。
4. **排除的伪根因**：不是 `@Positive` 写错了——对真实支付网关，0 元授权确实无意义。
   也不是 `@PositiveOrZero` 写错了——赠品行、100% 折扣行是合理业务。
   错的是**两者共存却没人负责调和**，以及调和的结果没有写在发布契约上。

## 复现（test-first）

```java
@Test
void anOrderWhoseTotalIsZeroDoesNotSilentlyTimeOut() {
  String orderId = commandBus.send(new PlaceOrder("CUST-1",
      List.of(new PlaceOrder.Line("SKU-1", 1, 0, "USD"))));      // 0 元

  // 期望二选一：要么下单当场被拒（400/422），要么支付被正常处理并确认。
  // 当前：201 → 停在 AWAITING_PAYMENT → PT2M 后 CANCELLED，理由是 PAYMENT_TIMEOUT。
  await().atMost(SETTLE).untilAsserted(() -> assertEquals("CONFIRMED", status(orderId)));
}
```

配一条更快的单元级复现（不必等 2 分钟）：

```java
@Test
void aZeroAmountAuthorizeIsRejectedByTheBus() {
  assertThrows(ConstraintViolationException.class,
      () -> commandBus.send(new AuthorizePayment("o-1", "op-1", 0L, "USD")));
}
```

## 修复

先定业务口径，再落约束——**不要只改一侧的注解让红变绿**：

- **若 0 元订单是合法业务**（推荐，赠品/全额折扣很常见）：
  `AuthorizePayment.amountMinor` 改 `@PositiveOrZero`，
  并在 `AuthorizationPolicy.decide` 里显式处理：`amountMinor == 0` → `Authorized`（无需网关往返）。
  这顺带演示了一个真实系统必有的分支。
- **若 0 元订单不合法**：把拒绝**前移到下单入口**——
  `PlaceOrder` / `PlaceOrderRequest` 改 `@Positive`，或在 `Order.place` 里加一条
  "订单总额必须为正"的 `Invariant`（放聚合里更好：这是订单自身的规则，不是支付的）。
  错误应在 201 之前发生，而不是两分钟后。

无论哪条，都要**把结论写进发布语言**：给 `ordering-api` 的 `PaymentRequested.amountMinor`
加上约束注解或 javadoc 明示值域，让下一个消费方不必再猜。

同时建议给 `PaymentTimedOut` 的取消理由留一条区分：
"支付超时"与"支付请求根本没被受理"对运维是两件事，
目前都记成 `PAYMENT_TIMEOUT`（见 [[issue-00068-stock-waits-have-no-deadline-and-can-park-forever]] 的可观测性讨论）。

## 验证结果

已修。**采用了"0 元订单合法"这条**（修复一节的推荐项）：赠品行与全额折扣行是真实业务，
把它们挡在下单入口反而是把一条合理的业务规则删掉。

**实际改动四处**：

1. `AuthorizePayment.amountMinor`：`@Positive` → `@PositiveOrZero`。
   javadoc 说明了为什么这条约束的违例不是"给调用方一个 400"——命令来自事件监听器，
   被拒的命令是一条毒消息，重试到进死信为止，而订单在另一侧等到 deadline。
2. `AuthorizationPolicy.decide`：新增 `amountMinor == 0 → Authorized` 的**独立分支**。
   行为与改前完全相同（`0 <= 50000` 本来就会授权），改的是它为什么被授权：
   从"恰好落在天花板以下"变成"没有东西要收，所以不做网关往返"。
   这一点值得强调——如果不写这个分支，某次调低天花板就会静默改变 0 元的行为。
3. `PaymentRequested` javadoc：**写下值域**（"零或更大"）。这是本 issue 真正的修复，
   前两条只是让两侧一致，这一条让下一个消费方不必再猜。
4. `PlaceOrder` / `PlaceOrderRequest` **未动**——它们本来就是对的。

**三条测试，逐条验证过会红**：

- `AuthorizePaymentBusValidationTest`（新建，payment-application）：
  照搬 ordering 侧 `PlaceOrderBusValidationTest` 的手法，手工装配真实的
  `RegistryCommandBus` + `ValidationCommandInterceptor`。三条用例：
  0 抵达 handler（改前红：`ConstraintViolationException: amountMinor: must be greater than 0`）、
  **负数仍被拒**、缺 `paymentOperationId` 仍被拒。
  后两条是防止"把约束删掉让红变绿"的对照——issue 的修复一节明确警告过这一点。
  为此给 `payment-application` 加了两个 test-scope 依赖
  （`aipersimmon-ddd-cqrs-spring-boot-starter`、`spring-boot-starter-validation`），
  注释与 ordering 侧那段逐字一致。
- `AuthorizationPolicyTest.authorizesAZeroAmountOutright`：钉住第 2 条那个分支。
- `OrderingFlowTest.aZeroAmountOrderIsConfirmedRatherThanQuietlyCancelledTwoMinutesLater`：
  端到端。**放进已有的 `OrderingFlowTest` 而不是新建测试类**，
  因为它的 `properties` 与嵌套 `RecorderConfig` 已经界定了一个上下文，
  新建一个会多起一对容器（[[issue-00092-each-test-context-starts-its-own-container-pair]]）。

**负向对照的实测输出**（把 `@PositiveOrZero` 改回 `@Positive`，备份原文件而非 `git checkout --`）：

```
OrderingFlowTest.aZeroAmountOrderIsConfirmedRatherThanQuietlyCancelledTwoMinutesLater
  ConditionTimeoutException: expected: <CONFIRMED> but was: <FULFILMENT_IN_PROGRESS> within 30 seconds
```

**与 issue 原稿的一处出入**：原稿的复现段写"停在 `AWAITING_PAYMENT`"。
`AWAITING_PAYMENT` 是**流程步骤**的名字，不是订单状态；订单状态停在 `FULFILMENT_IN_PROGRESS`。
测试注释已按实测更正。另外该测试类关掉了 deadline worker，所以对照里看到的是"流程永不终结"
而不是"两分钟后 CANCELLED"——两者是同一个缺陷的两种表现。

**未做的一条**：原稿"同时建议"把 `PaymentTimedOut` 的取消理由区分为
"支付超时"与"支付请求根本没被受理"。修完之后 0 元不再走超时路径，这条建议失去了它的触发场景，
留给一个真正需要区分的场景再做。

`mvn -o test -pl payment/payment-application,payment/payment-domain -am` 全绿；
`mvn -o test -pl start -am -Dtest=OrderingFlowTest` 4 条全绿。

## 关联

- [[report-00002-scaffold-ddd-review]]
- [[issue-00068-stock-waits-have-no-deadline-and-can-park-forever]]（deadline 目前掩盖了这个缺陷的严重度）
- [[decision-00014-cloudevents-integration-event-contract]]（发布语言应承载值域约定）
