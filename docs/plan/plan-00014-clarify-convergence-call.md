---
id: plan-00014-clarify-convergence-call
type: plan
status: resolved
implements: [spec-00001-AC-45.6, spec-00001-AC-45.7]
---

# Plan: 澄清骨架的收敛声明——落地第十二轮（FR-45 修订）

对 `spec-00001-FR-45` 第十二轮修订的实现：澄清任务指令新增收敛声明（最少
题数解决本阶段推进决策、饱和即收尾），判据由焦点行的停止条件承载（FR-48
第十二轮扩义，配置已先行于 commit `bbb24cf1`）。来自实测观察：连续澄清轮
出现越级提问与无收敛出题。

## Design

- [design-00001-docs-whiteboard](../design/design-00001-docs-whiteboard.md)
  §4 任务指令模板（本次改动在指令文本层，不触及模块结构与 API，无需修订
  design）。审计裁定的实现约束：收敛两行**不进 `SKELETON` 常量**、须排在
  焦点行之后（骨架的「本阶段推进决策」以焦点行为定义，先引用后定义会倒置；
  焦点行自带的 stop 条款由收敛行点名为逐类型判据，避免读成重复）。

## Tasks

- **T1 — 骨架收敛行**：`sessionTasks.ts` 的 `clarifyInstruction` 在焦点行
  之后、状态文件段之前插入收敛声明两行（英文，指令通体英文的既有裁定）：
  最少题数原则 + 饱和即收尾、判据指向上方焦点行；`sessionTasks.test.ts`
  镜像 AC-45.3/45.4 的 containment 断言补两条。
- **T2 — 测试与验收**：`npm test`、`npm run typecheck`、覆盖率门不降；写
  record（`parent` 指向本 plan）覆盖 FR-45 全部七条 AC（45.1…45.5 引既有
  测试，45.6/45.7 引新增），以本 plan 过 resolved 门收口。

## Detailed Acceptance Path

1. 新增两条 containment 断言通过 → verify: 测试名与指令文本。
2. 全量测试、typecheck、覆盖率门全绿 → verify: 命令退出码与阈值。
3. record 覆盖 FR-45 七条 AC，本 plan 经 `open → resolved` 放行 →
   verify: resolved 门通过。

## Out of Scope

- 澄清行为合规的工具侧校验（BR-11 产出校验，独立立项）。
- 焦点行内容本身（配置可随时重调，`bbb24cf1` 已落）。
- 题量的硬性数字上限（无依据的阈值不立）。
