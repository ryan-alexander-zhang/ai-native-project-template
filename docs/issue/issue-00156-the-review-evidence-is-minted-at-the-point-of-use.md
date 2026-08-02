---
id: issue-00156-the-review-evidence-is-minted-at-the-point-of-use
type: issue
role: main
status: open
---

# 评审"证据"在使用点自铸，削弱它要教的模式（P1，教学）

2026-08-02 第四轮评审发现（scaffold，ordering）。

## 现象

`ApproveReviewHandler`（ordering-application）在使用点现场制造证据：

```java
order.approveReview(new ReviewDecisionRef.Approval(idGenerator.newId(), id));
```

`decisionId` 不指向任何存储的评审记录，也不携带评审人身份。对照组是补偿路径——那里的
refs 是真凭据（信封 `messageId`），"证据承载类型"的课在那边是实的。评审分支演示了**形式**
（一个类型化的 ref）而没有**实质**（一个存在于别处的事实），读者可能得出"现场铸造证据
就是这个模式"的结论。现有 javadoc 只讨论了 id 生成方式的选择。

## 修复要求

在 handler（或 `ReviewDecisionRef.Approval` 的 javadoc）加一段取舍注释，风格对照
`Order.place` 的 trade-off 注（Order.java:90-99）：

- 言明这是 stand-in：真实系统里 Approval 应引用一条已存储的评审记录（评审单/工单/审批流
  实例）与作出决定的 principal；
- 言明为什么 scaffold 不建那张表（评审子域不在演示范围内，建了会喧宾夺主）；
- 一句点破判据：**证据承载类型的价值在于它指向一个独立存在的事实**——补偿 refs
  （envelope messageId）是范例，这里是权宜。

纯注释改动，不改行为。
