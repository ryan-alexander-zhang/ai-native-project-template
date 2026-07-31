---
id: issue-00138-the-transition-table-cannot-name-its-refusal
type: issue
role: main
status: open
---

# 转换表拒绝时报不出错误码，逼得聚合把同一条守卫写两遍

2026-07-30 全面评审（P1）。

## 问题

`Transitions.check`（`aipersimmon-ddd-core/.../state/Transitions.java:62-66`）只调用
`IllegalStateTransitionException` 的二参构造器；带 `ErrorCode` 的构造器
（`IllegalStateTransitionException.java:17`）从 `Transitions` 路径**不可达**，实为死代码。
于是 `Order.beginFulfilment`/`confirm`/`ship` 的非法转换以无码异常到达边缘，而 cancel/review
路径每条拒绝都有 `OrderingErrorCode`——同一聚合两种错误契约。

连带症状：`Order.approveReview`（`Order.java:160-165`）为了给出有码错误
`ORDER_NOT_AWAITING_REVIEW`，先手写状态守卫，随后的 `RULES.check(...)` 在逻辑上永不失败——
同一条规则写了两遍，是框架能力不足逼出的冗余。

另一处 API 缺陷：`allow()` 在发布后仍可变（`Transitions.java:47-50`，`HashMap` 非线程安全，
无 freeze/build 步骤）。当前 `private static final` 用法安全，但 API 本身允许运行时改表。

## 根因（第一性）

- 期望：`ErrorCode` javadoc 自己说码要"从抛出点固定并原样到边缘"。
- 分歧机制：框架最常用的守卫（转换表）恰恰无法携带码——声明转换时没有地方放它。
- 真根因：`allow(from, to)` 的签名少了一个参数。

## 复现（先写失败测试）

断言 `Transitions.of().allow(A, B, SOME_CODE)` 存在且 `check` 失败时异常携带该码；
`Order` 四个机械转换的非法调用断言错误码非空。修复前编译不过/断言失败。

## 改法

```java
public Transitions<S> allow(S from, S to, ErrorCode code) { ... }  // 保留无码重载
public void check(S from, S to) {
  if (!permits(from, to)) throw new IllegalStateTransitionException(codeFor(from, to), from, to);
}
```

`Order` 四个机械转换各配一个码，`approveReview` 的手写守卫删除。同时给 `Transitions` 加
freeze 语义（`build()` 返回不可变实例），或 javadoc 明确"必须在类初始化时完成构建"。

## 验证结果

未修复。
