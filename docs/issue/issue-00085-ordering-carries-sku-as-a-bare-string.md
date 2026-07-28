---
id: issue-00085-ordering-carries-sku-as-a-bare-string
type: issue
role: main
status: open
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

未修。本 issue 由 [[report-00002-scaffold-ddd-review]] 落盘，尚未实施。

## 关联

- [[report-00002-scaffold-ddd-review]]
- [[decision-00005-package-per-aggregate]]
- [[issue-00086-customer-is-an-aggregate-nothing-writes]]（另一处"外来概念如何在本上下文建模"）
