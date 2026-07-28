---
id: issue-00085-ordering-carries-sku-as-a-bare-string
type: issue
role: main
status: resolved
parent: report-00002-scaffold-ddd-review
---

# 同一个概念两种建模精度：inventory 有 Sku 值对象，ordering 用裸 String

## 问题（现状，file:line 为证）

- **等级：Low（不出错；但它是同一份 scaffold 里对"值对象"这一构件的两种示范）**。
- inventory 侧把 SKU 建成了值对象（`Sku.java:7-13`）：

```java
public record Sku(String value) implements Identifier {
  public Sku { if (value == null || value.isBlank()) throw new DomainException("sku required"); }
}
```

- ordering 侧全程用裸 `String`：
  - `LineData.sku`（`LineData.java:10`）
  - `OrderLine.sku`（`OrderLine.java:15`，校验散在构造器里 `:20-22`）
  - `OrderHasDistinctSkus` 的去重（`:21`，`map(OrderLine::sku).collect(toSet())`）
  - `ManualReviewPolicy.RESTRICTED_SKUS` 与它的 `contains(line.sku())`（`:23,30`）
- 对比同一个包里的其它标识：`OrderId`、`CustomerId` 都是 record 值对象
  （`OrderId.java:7-13`、`CustomerId.java:7-13`），形态与 `Sku` 完全一致。
  **只有 SKU 没有被提升。**
- 具体代价（都不大，但都真实）：
  - 校验重复：`Sku` 里一次、`OrderLine` 构造器里一次（`:20-22`），两处规则可以分叉；
  - `ManualReviewPolicy` 用 `Set<String>` 装 SKU（`:23`），
    类型上无法与"客户 id 集合"或任何别的字符串集合区分；
  - 跨上下文边界处，`OrderReadyForFulfilment.Line.sku`（`ordering-api`）是 `String`，
    到 inventory 侧才被包成 `Sku`（`ReserveStockHandler.java:83,102`）——
    这一步本身是对的（发布语言应当是扁平的），
    但它掩盖了 ordering 内部**从未**有过 `Sku` 这个类型这件事。

## 根因（第一性）

1. **观察 vs 期望**：期望"同一个领域概念在所有上下文里获得同等的建模待遇（或有明确理由不这样）"；
   实际"一个上下文把它建成了类型，另一个把它当字符串"。
2. **最小机制**：`OrderLine` 是**包私有**的内部实体（`OrderLine.java:13`），
   它的 `sku()` 也是包私有（`:31`）。因为外界看不到，
   "用什么类型"看起来像纯内部选择，压力最小的选择就是 `String`。
3. **真根因**：SKU 在 ordering 里是**外来概念**——它的定义、合法性、生命周期都属于 inventory。
   ordering 只是转述它。于是"要不要为一个别人的概念建类型"没有明显答案，
   默认就落到了 `String`。
   这其实是一个**合理的疑问**，只是它从未被回答过，也没被记录过。
4. **排除的伪根因**：不是 `Sku` 应该被共享/上提到公共模块——
   跨上下文共享领域类型正是要避免的（`ordering-api` / `inventory-api` 的分离就是为此）。
   ordering 若要类型，应当有**自己的** `Sku`，与 inventory 的同名不同源。

## 复现（test-first）

没有可失败的行为测试，用一条 ArchUnit 规则表达约定：

```java
@ArchTest
static final ArchRule domainIdentifiersAreValueObjects =
    noFields().that().areDeclaredInClassesThat().resideInAPackage("..domain..")
        .and().haveNameMatching(".*(Sku|Id|Code)")
        .should().haveRawType(String.class)
        .because("领域标识用值对象承载，校验只写一次（对照 OrderId / CustomerId / Sku）");
```

当前 `OrderLine.sku`、`LineData.sku` 会让它变红。

## 修复

二选一，**关键是把结论写下来**：

1. **提升（推荐）**：在 `ordering-domain` 里加一个自己的 `Sku` 值对象
   （与 inventory 的同名但独立，各自演进），
   `LineData` / `OrderLine` / `ManualReviewPolicy` 改用它。
   收益：校验只写一处；`OrderHasDistinctSkus` 与 `RESTRICTED_SKUS` 都获得类型；
   并且顺带演示了"两个上下文对同一概念各建各的类型"这个 DDD 要点——
   这恰恰是当前 scaffold 缺的一课，而现成的对照材料（inventory 的 `Sku`）已经在仓库里了。
2. **保留 String 并说明理由**：在 `LineData` 的 javadoc 里写明
   "SKU 是 inventory 的概念，ordering 只透传，故不建类型"，
   并在上面那条 ArchUnit 规则里豁免。

无论哪条，都应顺手把 `OrderLine` 构造器里那段 SKU 校验（`:20-22`）与
未来的 `Sku`（或注释）对齐，避免规则两处声明。

## 验证结果

已修。选了**方案 1（提升）**：ordering 有了自己的 `Sku`，与 inventory 的同名、独立、各自演进。

**放在 `ordering-domain/shared/`，与 `Money` 并列，而不是 `domain/order/`**，
理由是它**不是**任何 ordering 聚合的标识：

- inventory 的 `Sku implements Identifier`——它是 `Stock` 聚合的身份；
- ordering 的 `Sku` 只 `@ValueObject`，**不实现 `Identifier`**——在这里它标识不了任何 ordering 拥有的东西，
  它是订单行携带的一个值。

这个差别正是本 issue 想演示的那一课的落点：同一个概念，两个上下文，
连"它是不是一个身份"的答案都不一样。写进了 `Sku` 的 javadoc。

**改动范围（转换点收在一处）**：

| 位置 | 变化 |
|---|---|
| `LineData.sku` / `OrderLine.sku` / `OrderLine` 构造器 | `String` → `Sku`；构造器里那段 blank 校验**删掉**（`Sku` 已管） |
| `ManualReviewPolicy.RESTRICTED_SKUS` | `Set<String>` → `Set<Sku>` |
| `PlaceOrderHandler` | `new Sku(line.sku())`——**原语变类型的那一个边界** |
| `FulfilmentTrigger` / `MyBatisOrders` | 出站时 `.value()` 拆包 |
| `OrderReadyForFulfilment.Line.sku` / `StockAvailabilityGateway` | **不动**，仍是 `String` |

最后一行是有意的：发布契约与跨上下文端口保持扁平，
消费方不该为了读 ordering 的事件而依赖 ordering 的类型。

**ArchUnit 规则收窄了，与原稿提议不同**。原稿写的是
`haveNameMatching(".*(Sku|Id|Code)")` 不得为 `String`。**照抄会当场红三处，且那三处都是对的**：
`ReservationFailureRef.reasonCode`、`ReservationFailureRef.failureId`、`PaymentDeclineRef.declineCode`——
它们承载的是别的上下文的不透明码，ordering 既不定义也不解释，为它们造类型是为建模而建模。
实际落地的规则只管**字段名恰好是 `sku`** 的：两个上下文都覆盖，也不需要任何豁免。
"一条上来就要豁免三次的规则不是规则"这句写进了它的 javadoc。

**一个必须记下的操作陷阱**：`mvn -o test-compile`（不带 `clean`）**BUILD SUCCESS，是假的**。
`LineData` 是主源码，改动后主源码重编了；测试源码没变，
maven-compiler-plugin 的增量判断就认为 test 无需重编，于是 20 处
`new LineData("SKU-1", ...)` 一个都没报。加上 `clean` 才暴露出来。
**改了被测试大量引用的类型签名之后，验证必须带 `clean`。**

**测试**：新增 `SkuTest`（相等性、blank 拒绝、`toString` 返回裸值——
`ManualReviewPolicy` 会把它拼进给运维看的文本，默认的 `Sku[value=SKU-1]` 会漏出包装类型）。
既有 8 个 domain 测试类的构造调用一并更新；
`OrderLineAndInvariantTest` 里"blank sku 被拒"这条**从 `OrderLine` 移到了 `Sku`**——
校验只写一处，测试也该只测那一处。

验证：`mvn -o clean verify`（全 reactor）通过。

## 关联

- [[report-00002-scaffold-ddd-review]]
- [[decision-00005-package-per-aggregate]]
- [[issue-00086-customer-is-an-aggregate-nothing-writes]]（另一处"外来概念如何在本上下文建模"）
