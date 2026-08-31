---
id: issue-00079-review-decision-id-bypasses-the-id-generator
type: issue
status: resolved
blocks: [report-00002-scaffold-ddd-review]
---

# 审核决定的 id 绕过 IdGenerator 直接用 UUID.randomUUID()

## 问题（现状，file:line 为证）

- **等级：Low（该 id 不落库，无索引代价；但 scaffold 是"一个概念一种写法"的示范，此处出现了第二种写法）**。
- `ApproveReviewHandler` 直接调 JDK（`ApproveReviewHandler.java:44`）：

```java
order.approveReview(new ReviewDecisionRef(UUID.randomUUID().toString(), id, true));
```

- 而同层的另外两个 handler 都走注入的 `IdGenerator`：
  - `PlaceOrderHandler.java:103` —— `new OrderId(idGenerator.newId())`，
    上面还有一条注释解释为什么（`:101-102`，引 issue-00054）；
  - `ReserveStockHandler.java:86` —— `new ReservationId(idGenerator.newId())`，同样带注释。
- `AggregateIdIsTimeOrderedTest`（`:69-88`）只覆盖 `OrderId`，
  所以这处不一致不会被任何测试发现。
- 两个次要后果：
  - `ApproveReviewHandler` 因此**没有** `IdGenerator` 依赖，id 无法在测试里被替换为确定值；
  - decision-00019 的意图是"框架内 per-row id 统一由 `IdGenerator` 铸造"，
    这里出现了一个例外，而例外没有被记录为例外。

## 根因（第一性）

1. **观察 vs 期望**：期望"生成标识符只有一条路径"；实际"有两条，凭作者当时是否想起来选择"。
2. **最小机制**：`UUID.randomUUID()` 是 JDK 静态方法，随手可用、无需注入；
   `IdGenerator` 需要构造器参数。阻力更小的那条路径不是约定的那条。
3. **真根因**：decision-00019 的约束是**用意图**（"time-ordered id"）表达的，
   而不是用**可执行的规则**表达的。`AggregateIdIsTimeOrderedTest` 把它钉在了
   "订单主键"这一个点上；`ReviewDecisionRef` 不是主键，所以落在钉子之外——
   于是"用哪种 id"重新变成了每次都要现想的问题。
4. **排除的伪根因**：不是"证据 id 不需要时间有序"——这个论点成立
   （`ReviewDecisionRef` 不落库、不做索引键），
   但它是**修复后应当写下的理由**，而不是当前状态的解释：现在没有任何地方写着这句话。

## 复现（test-first）

用一条 ArchUnit 规则钉住约定，而不是逐个 handler 写测试：

```java
@ArchTest
static final ArchRule idsComeFromTheIdGenerator =
    noClasses().that().resideInAPackage("..application..")
        .should().callMethod(UUID.class, "randomUUID")
        .because("标识符由 IdGenerator 铸造（decision-00019）；"
               + "确有例外时在此规则上显式豁免并写明理由");
```

当前 `ApproveReviewHandler` 会让它变红。

## 修复

二选一，**任选其一都必须留下理由**：

1. **对齐（推荐）**：注入 `IdGenerator`，改成 `new ReviewDecisionRef(idGenerator.newId(), id, true)`。
   代价是一个构造器参数，收益是"标识符只有一条路径"这句话在整个 scaffold 里为真，
   且该 handler 的 id 在测试里可控。
2. **显式豁免**：保留 `randomUUID`，但在方法上写明"这是进程内证据 id，不落库、不做索引键，
   因此不需要时间有序"，并在上面那条 ArchUnit 规则里加白名单。

无论哪条，都建议把 `AggregateIdIsTimeOrderedTest` 的覆盖从"订单主键"
扩到"所有落库主键"（目前 `ReservationId` 也没被断言过）。

## 验证结果

已修。采用修复方案 1（对齐）。

- `ApproveReviewHandler` 注入 `IdGenerator`，`UUID.randomUUID().toString()` →`idGenerator.newId()`。
- 按方案 1 的要求留下了理由：类 javadoc 说明这个 id **不是**主键、时间有序本身在此不产生收益，
  统一铸造的价值是"一种做法"而非性能。这正是本 issue 真正要留下的东西。
- 验证：`mvn -o compile` 通过；`ConcurrentApprovalTest`（走 approve-review 全链路，含真 PostgreSQL
  与 Kafka）通过，证明 `IdGenerator` bean 在完整上下文中可解析。
- 未做：把约定固化成 ArchUnit 规则（复现一节给出的那条），留待与其它结构断言一并加入。

## 关联

- [report-00002-scaffold-ddd-review](../report/report-00002-scaffold-ddd-review.md)
- [decision-00019-time-ordered-uuidv7-identifiers](../decision/decision-00019-time-ordered-uuidv7-identifiers.md)
- [issue-00054-sample-aggregate-ids-use-random-uuid](issue-00054-sample-aggregate-ids-use-random-uuid.md)（同一约定的上一轮修复，这次是它漏掉的一处）
