---
id: plan-00005-whiteboard-requirement-panel
type: plan
status: resolved
implements: [spec-00001-FR-31, spec-00001-FR-32, spec-00001-FR-33, spec-00001-FR-34, spec-00001-FR-35, spec-00001-FR-36, design-00001-docs-whiteboard, design-00002-whiteboard-ui]
---

# Plan: 检视面板、覆盖状态与子画布

> 让正式编号条目（decision-00004 §1 增补前实测 186 条）与验收行（同口径 136 条）
> 第一次在白板上可读：选中 spec/rule 即见条目与覆盖三态，悬停即见证据在哪份
> record，下钻子画布审整条验收链路。顶层图不因条目增加节点或边；FR-2 修订带来
> 的细粒度边转正（下方第 3 条）是唯一例外。

## Design

- [design-00001-docs-whiteboard](../design/design-00001-docs-whiteboard.md)
  §2（Doc Repository 的条目/验收行解析与覆盖推导）、§7（`GET /api/docs/:id/items`
  与 edge 的 `declaredTarget`）。
- [design-00002-whiteboard-ui](../design/design-00002-whiteboard-ui.md)
  §2（右槽三值）、§9（检视面板、覆盖图标、悬停联动、子画布）。
- [decision-00004-whiteboard-requirement-panel](../decision/decision-00004-whiteboard-requirement-panel.md)
  —— 面板为主/子画布为辅的理由、覆盖三态口径、§5 的三项裁定。

## Tasks

代码位于 `tools/whiteboard/`。T1 先行；T2 依赖 T1，T3 依赖 T2；T4 的画布切换
与布局依赖 T1、可与 T2/T3 并行，但其入口与 `AC-35.1`/`35.5`/`36.2` 断言在
检视面板上，收口依赖 T2 先在。

| # | 任务 | 交付 | 覆盖 |
| --- | --- | --- | --- |
| T1 | 数据层与 FR-2 修订（含呈现） | Doc Repository 解析需求条目（列表项 + 决策表行两种形态）、AC 归属（按「(所验条目 id)」标注）、验收行识别（首列被验 id + 测试/结果列的表格行，修订对照表不算）、覆盖三态推导（判定次序与零 AC、条目级行的口径全在 `spec-00001-FR-32`，**不在实现里另行裁定**）；关系目标两段解析（文档 id → 条目/AC id 落所属文档），edge 增加 `declaredTargets` 列表、同字段多值落同一文档合并为一条边（`AC-28.5`）；**FR-2 修订的呈现侧一并交付**：细粒度边落所属文档节点且不带异常标记（`AC-2.5`）、关系列表按 `declaredTargets` 逐项列出且可点击定位到所属文档；新端点 `GET /api/docs/:id/items`（载荷字段见 design-00001 §7） | spec FR-2（修订）、FR-28（`AC-28.5`）、FR-31…FR-33 数据侧；design-00001 §2/§7 |
| T2 | 检视面板 | 右槽三值状态与编辑器优先（design-00002 §2/§9）；条目行 = 覆盖图标（带可访问名）+ 等宽 id + 正文两行截断 + AC 计数；「无法归属」区；异常 spec/rule 节点同样出面板 | spec FR-31…FR-33 呈现侧 |
| T3 | 悬停联动 | 面板行 hover 与键盘 focus 同通路：相关 record 边转 `edge--emphasis`、标签换被引 AC id（多条并列），其余压弱；离开回 FR-29 的选中态呈现 | spec FR-34 |
| T4 | 子画布 | 同一 React Flow 实例切换数据集（复用 `/items` 载荷，不加端点）；条目 \| AC \| 验收行三列布局；面包屑（仅子画布出现）与「Board」返回即选中 | spec FR-35、FR-36 |
| T5 | 测试 | 39 条 AC 全部落测：`AC-2.5`/`2.6`、`AC-28.5`、`AC-31.1`…`31.9`、`AC-32.1`…`32.10`、`AC-33.1`…`33.3`、`AC-34.1`…`34.6`、`AC-35.1`…`35.6`、`AC-36.1`/`36.2` | 全部 |
| T6 | 收尾 | 新建 `record-00004` 承载验收，`verifies` 列具体 requirement id（`spec-00001-FR-2` 修订部分、`FR-28` 的 `AC-28.5` 与 `FR-31`…`FR-36`）；更新 issue-00005 §8 的残留状态为已消除（decision-00004 §5 裁定一交办本 plan）；实测项见下方第 5 条 | — |

## Detailed Acceptance Path

1. **每任务完成即验证**：`npm test` 全绿、`npm run typecheck` 无错、
   `npm run build` 通过。
2. **新 AC**：上表 T5 的 39 条，每条有对应通过的测试。
3. **不回归**：FR-1…FR-30 的既有 AC 仍全部通过。以下**预期变化不是回归**，
   均为 FR-2 修订所致：(a) `record-00003` 的三条细粒度 `verifies` 引用由异常
   转正并合并为一条边——断言异常计数、边数或边集合的既有用例按新语义更新观察
   点；(b) 关系列表中这三项从「列出但不可点击」（issue-00005 的处置）变为可
   点击并定位到 `spec-00001`——以 record-00003 为夹具的 toolbar 用例随之更新；
   (c) 该边被选中时呈现正常强调态而非「强调 + 异常」叠加（`AC-29.8` 的夹具若
   用了它须换一条真断链）。`AC-2.2` 仍须成立——真正无法解析的引用照旧异常。
4. **覆盖率**：自有代码仍 ≥90% 行/分支/函数。
5. **实测核对**：用本仓真实文档开一次白板——(a) 选中 `spec-00001`，36 条 FR 的
   面板读得动（decision-00004 §4 未验前提一）；(b) 悬停条目时边标签在关系名与
   AC id 之间切换无可感抖动（未验前提二）；(c) 覆盖缺口一眼可见——选中
   `spec-00001` 应见本 plan 新增的 FR（FR-31…36 等）呈「未覆盖」、选中
   `rule-00001` 应见 BR-19 呈「未通过」（record-00001 对 AC-19.x 记了 `n/a`，
   口径即如此）；实测值与 T1 落地时的数据层核算对照，不符即查；(d) 下钻
   `spec-00001` 子画布，验收链路与断口可辨。任一不成立，据实记入
   `record-00004` 并提出下一步，不得默认通过。
   注：decision-00004 §1 的「BR-3…BR-9 共 7 条」是宽口径下的当时值；其中一部分
   实为 record-00001 一条区间行的格式问题（已展开为逐条行），严格口径的当前值
   以白板呈现为准。
6. **收尾门槛**：由未参与实现的 subagent 按文档核验每条 GWT 有通过的测试、无
   unverified 条目；`record-00004` 建好并链上 GWT id 后本 plan 方可 `resolved`。
   任何 gap 阻塞 `resolved`。
