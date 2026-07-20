---
id: issue-00043-analysis-00012-stale-definition-placement
type: issue
role: main
status: open
parent: analysis-00012-multi-module-process-manager-layering
---

# `analysis-00012` §1 现在时仍称 Definition 在 application 层，与已落地代码不符（文档漂移，非自相矛盾）

## 问题（现状，file:line 为证）

- **等级：Low**（文档漂移，非代码缺陷，亦非内部矛盾）。
- 一次 review 反馈称 [`analysis-00012`](/Users/ryan/GitHubProjects/ryan-alexander-zhang/ai-native-project-template/docs/analysis/analysis-00012-multi-module-process-manager-layering.md) “前半段说 Definition 在 application、后半段说已迁入 provider，自相矛盾”。**该定性不成立**：
  - 文档 `:11` 自述是“一份『读代码、不改代码』的梳理与 review 文档”——§1–§6 描述的是**梳理当时的代码现状**。
  - §1（`:31-33` “结论”，及组件表 `:56`）描述当时现状：决策大脑 `OrderFulfilmentDefinition` 落在 `ordering-application`。
  - §6（`:271` 表行标 `⚠️`）把该落点标为**待评审偏差**。
  - §7（`:286`）记录**采纳决策**：“Definition 落点 —— 抽独立模块 `ordering-process-jdbc`（已定）”。
  - `:281-282` 有一段 blockquote **显式桥接**两半：“这一条已在 §7 定案 …… 其余章节是对既有代码的忠实描述。”
  - 故文档结构是“观察现状 → review 发现 → 采纳决策”的叙事，并已自我标注，**不是**未解决的内部矛盾。
- **真实问题是文档漂移**：§7 定的决策此后**已执行**——被 review 的文件现已位于
  [`ordering-process-jdbc/.../fulfilment/OrderFulfilmentDefinition.java`](/Users/ryan/GitHubProjects/ryan-alexander-zhang/ai-native-project-template/aipersimmon-ddd-scaffold/multi-module/ordering/ordering-process-jdbc/src/main/java/com/example/ordering/process/fulfilment/OrderFulfilmentDefinition.java:1)（`package com.example.ordering.process.fulfilment`）。于是 §1 的**现在时**表述“Definition …… 就在 `ordering-application` 里”已与代码不符。

## 根因（第一性）

1. **观察 vs 期望**：期望“读者读 §1 得到的当前落点结论与仓库现状一致”；实际“§1 现在时结论描述的是决策落地**之前**的旧现状”。
2. **最小机制**：§1 用**现在时**陈述一个自 §7 决策落地后即过期的事实，而 §7 的决策已在代码中执行（文件实际 package/路径已在 provider 模块）。桥接段 `:281-282` 只在句末补丁式提示“已在 §7 定案”，并未回改 §1 本身的时态。
3. **真根因**：present-tense 的“现状描述”被一个**已实现**的决策带旧——是**文档与代码的时间漂移**，而非 reviewer 所称的“文档内部自相矛盾”。排除“逻辑矛盾”这一症状：§1 与 §7 在文档语境（现状→决策）下并不冲突，冲突只发生在 §1 与**当前代码**之间。

## 复现（test-first）

对纯文档缺陷，失败测试不适用（无可执行断言面）；按 `README.md` 记录替代验证：

- **doc ↔ code 交叉核读**：`OrderFulfilmentDefinition` 的实际文件路径与 `package` 声明均已在 provider 模块
  `ordering-process-jdbc`，而 `analysis-00012` §1（`:31-33`）/组件表（`:56`）仍以现在时称其位于 `ordering-application`。
  二者并置即为漂移证据。
- 该交叉核读可作为轻量回归守卫：只要 §1 现在时结论与 `OrderFulfilmentDefinition` 真实所在模块不一致，即判定文档过期。

## 修复

文档侧对齐，不动代码：

1. 给 §1 结论与组件表 `:56` 加时态/前言标注，如“（历史现状，已按 §7 迁至 `ordering-process-jdbc`）”，或直接把描述改为过去时，使读者不再把 §1 误读为当前落点。
2. 可选：在 §1 顶部一句话前言指明“§1–§6 为决策落地前的现状梳理，最终落点见 §7”，与 `:281-282` 的桥接呼应，避免同类漂移再次被误读为矛盾。

## 关联

- [[analysis-00012-multi-module-process-manager-layering]]（本文件；漂移出现在其 §1/§6/§7 的叙事与已落地代码之间）
- [[issue-00035-order-fulfilment-definition-ignores-step]]（同一 `OrderFulfilmentDefinition`，provider 模块内的状态机缺陷）
