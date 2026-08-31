---
id: issue-00081-openapi-examples-name-a-status-that-does-not-exist
type: issue
status: resolved
blocks: [report-00002-scaffold-ddd-review]
---

# OpenAPI 示例里的订单状态是一个不存在的值，id 示例也自相矛盾

## 问题（现状，file:line 为证）

- **等级：Low（不影响运行，但它污染的是**对外发布的机器可读契约**，而不是内部注释）**。
- `OrderSnapshot` 的 `@Schema(example = ...)`（`OrderSnapshot.java:10-18`）：

```java
@Schema(description = "Order identifier.", example = "ord-123")             String id,
@Schema(description = "Current order status.", example = "PLACED")          String status,
```

  **`PLACED` 不是 `OrderStatus` 的合法值。** 枚举只有六个：
  `AWAITING_REVIEW` / `READY_FOR_FULFILMENT` / `FULFILMENT_IN_PROGRESS` /
  `CONFIRMED` / `SHIPPED` / `CANCELLED`（`OrderStatus.java:16-34`）。
- 更值得注意的是这个值**为什么**在那里：`OrderStatus` 的注释说这套状态
  "deliberately richer than a single `PENDING`"（`:4-6`），
  README 也反复强调 "placed" 与 "ready for fulfilment" 是两个不同的事实（`README.md:60-62`）。
  `PLACED` 正是被这次建模**刻意去掉**的那个笼统状态——它残留在示例里，
  等于在对外契约上把这项建模决策又抹掉了一次。
- id 示例自相矛盾：
  - `OrderSnapshot.java:10` → `example = "ord-123"`
  - `OrderListItem.java:17` → `example = "0197c1e2-0a3b-7c4d-8e5f-6a7b8c9d0e1f"`（UUIDv7，正确）
  - `OrderController.java:75,129` → `example = "ord-123"`

  同一个 `orderId` 在同一份 `/v3/api-docs` 里有两种形态，
  而其中 `ord-123` 那种是系统永远不会产生的（id 恒为 UUIDv7，见
  [decision-00019-time-ordered-uuidv7-identifiers](../decision/decision-00019-time-ordered-uuidv7-identifiers.md)）。
- 币种示例也不一致：`OrderSnapshot.java:18` 用 `CNY`，
  `OrderListItem.java:22` 用 `USD`，而种子数据与全部测试都是 `USD`。

## 根因（第一性）

1. **观察 vs 期望**：期望"发布的 API 示例是系统真实会产生的值"；
   实际"示例是手写的占位字符串，与实现无关联"。
2. **最小机制**：`@Schema(example = "...")` 是**字面量字符串**，
   编译器、springdoc、测试都不会把它与 `OrderStatus` 或 id 格式做任何比对。
3. **真根因**：这些示例写于状态机重构（`PLACED` → 六态）与 id 迁移（随机 → UUIDv7）**之前**，
   两次重构都没有把"示例值"算进影响面——因为没有任何机制把它们算进去。
   它与 [issue-00078-six-places-still-describe-the-repositories-as-in-memory](issue-00078-six-places-still-describe-the-repositories-as-in-memory.md)
   是同一类漂移，区别在于**这一处漂进了对外契约**，而那一处只漂在内部注释里。
4. **排除的伪根因**：不是 `@Schema` 用错了位置——
   在 application 层的读模型上标注是有意的、且有 ArchUnit 规则背书
   （只有 domain 被禁止依赖 OpenAPI，见 `ordering-application/pom.xml:62-66`）。

## 复现（test-first）

```java
@Test
void everyStatusExampleInTheApiDocsIsARealOrderStatus() {
  JsonNode docs = http.getForObject("/v3/api-docs", JsonNode.class);
  Set<String> legal = Arrays.stream(OrderStatus.values()).map(Enum::name).collect(toSet());

  for (JsonNode schema : docs.at("/components/schemas")) {
    JsonNode status = schema.at("/properties/status/example");
    if (!status.isMissingNode()) {
      assertTrue(legal.contains(status.asText()),
          () -> "API 文档给出的示例状态不存在于 OrderStatus：" + status.asText());
    }
  }
}
```

当前因 `PLACED` 变红。id 一条可类比断言 `UUID.fromString(example).version() == 7`。

## 修复

1. `OrderSnapshot.java:13` → `example = "FULFILMENT_IN_PROGRESS"`
   （或 `CONFIRMED`；总之取一个真实值）。
2. 三处 `ord-123` → 与 `OrderListItem` 一致的 UUIDv7 字面量。
3. 币种统一为 `USD`，与种子和测试一致。
4. **更好的做法**：`status` 字段不写 `example`，改成
   `@Schema(implementation = OrderStatus.class)` 或显式 `allowableValues`，
   让 springdoc 从枚举生成——示例就再也不会与实现分叉。
   这一步才是真正消除这类缺陷的做法，上面三条只是修当下的值。

## 验证结果

已修。

- `OrderSnapshot.status` 采用修复第 4 条（更好的做法）：加 `allowableValues` 枚举全部六个合法状态，
  `example` 改为真实值 `FULFILMENT_IN_PROGRESS`。示例与实现从此有一处显式对照。
- id 示例三处（`OrderSnapshot`、`OrderController` 的两个 `@Parameter`）统一为 UUIDv7 字面量，
  与 `OrderListItem` 一致；币种由 `CNY` 改为 `USD`，与种子数据和全部测试一致。
- 验证：`mvn -o compile`、`spotless:check` 通过；`ApplicationSmokeTest` 通过
  （springdoc 反射这些注解构建文档，上下文能起即证明注解合法）。
- 未做：复现一节那条"扫 /v3/api-docs 校验示例状态合法"的断言。`allowableValues` 已经让这类漂移
  很难再发生，但该断言仍值得补。

## 关联

- [report-00002-scaffold-ddd-review](../report/report-00002-scaffold-ddd-review.md)
- [decision-00019-time-ordered-uuidv7-identifiers](../decision/decision-00019-time-ordered-uuidv7-identifiers.md)
- [issue-00078-six-places-still-describe-the-repositories-as-in-memory](issue-00078-six-places-still-describe-the-repositories-as-in-memory.md)（同类漂移，但只在内部注释）
