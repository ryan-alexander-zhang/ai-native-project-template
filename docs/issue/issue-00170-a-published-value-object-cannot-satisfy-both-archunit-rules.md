---
id: issue-00170-a-published-value-object-cannot-satisfy-both-archunit-rules
type: issue
role: main
status: open
---

# 一个"发布出去的值对象"无法同时满足库自己的两条 ArchUnit 规则（P2，规则集）

2026-08-04 写 S24 的 samples 时撞到（`aipersimmon-ddd-samples/s24-add-bounded-context`）。
不是运行时缺陷，是**规则集内部的一处冲突**：采纳了库两条规则的项目，必须在"发布出去的类型不能标
`@ValueObject`"和"跨上下文引用不走 api"之间选一个。

## 现象（实测）

多上下文布局下有两类值对象天然住在 `..api..`：

| 类型 | 为什么必须在 api |
| --- | --- |
| 发布出去的标识（`CouponCode`） | 别的上下文要能持有引用；它同时是聚合的 `id()`，所以必须 `implements Identifier` |
| 共享内核的值（`Money`） | 几个上下文共用它；`sharedkernel` 在 `BoundedContextRules` 眼里就是一个上下文，所以别人只能经 `sharedkernel.api` 用它 |

两者都标 `@ValueObject` 之后，`AiPersimmonDddRules.all()` 直接红：

```
Class <...coupons.api.CouponCode> does not reside in a package '..domain..'
Class <...sharedkernel.api.Money>  does not reside in a package '..domain..'
```

反过来把 `Money` 从 `sharedkernel.api` 挪到 `sharedkernel`（去掉 api 层），
`BoundedContextRules.dependOnEachOtherOnlyThroughApi` 红 **82 处**（实测，S24 的负向对照 2）。

**所以两条规则同时开着时，这两个类型无解**，只能把 `@ValueObject` 摘掉——而摘掉之后
`valueObjectsShouldBeImmutable` 也就不再覆盖它们，恰好是最暴露在外的那两个类型失去不可变性检查。

## 原因（两条规则各自都对，只是没有为"已发布"留出口）

- `aipersimmon-ddd-archunit/.../BuildingBlockRules.java:25-33`
  `domainBuildingBlocksShouldResideInDomain()`：`@AggregateRoot` / `@Entity` / `@ValueObject` 必须在
  `..domain..`。javadoc 的理由是"每个都标记一个模型概念，所以属于模型，绝不在 application /
  infrastructure / **interface** 层"——它列举的是那三层，**没有把 `api` 算进来**，因为这条规则是
  无参的、进 `all()` 的，不知道有"已发布契约"这回事。
- `aipersimmon-ddd-archunit/.../BoundedContextRules.java:44-52` 要求跨上下文只经 `..api..`。

## 库自己已经有这个区分，只差值对象这一对

`EventRules` 里恰好有现成的先例，一模一样的问题已经解过一次：

- `domainEventsShouldStayInDomain()`（`EventRules.java:51`）——内部事实留在 domain；
- `integrationEventsShouldResideInApi()`（`EventRules.java:170`）——**已发布**的事实住在 api。

也就是说库已经承认"同一个概念，内部的留在 domain，发布的住在 api"。值对象没有这一对。

## 建议（两条路，都不动 `all()` 的语义）

1. **让 `domainBuildingBlocksShouldResideInDomain()` 放行 `..api..`**，理由与
   `integrationEventsShouldResideInApi` 同源：一个 published 值对象是被刻意暴露的模型词汇，不是层次泄漏。
   风险：单上下文项目里会放松一点约束（有人把值对象丢进随便一个叫 api 的包）。
2. 或者**加一条平行的 opt-in 规则** `publishedValueObjectsShouldResideInApi()`，并把
   `domainBuildingBlocksShouldResideInDomain` 的 `@ValueObject` 部分参数化成"domain 或 api"。

倾向第 1 条：`@AggregateRoot` 与 `@Entity` 仍然只允许 domain（它们确实不该被发布），只放开
`@ValueObject`。

## sample 侧的现状（本地绕法，已标注）

`CouponCode` 与 `Money` 都**不标** `@ValueObject`，并在各自 javadoc 里指向本 issue；
`ArchitectureTest.thepublishedTypesAreStillImmutable` 手写补回不可变性检查
（`..api..` 里的顶层类必须只有 final 字段）。这条比注解弱：它要每个项目自己写一遍。

相关：[[analysis-00041-samples-add-bounded-context]] §3。
