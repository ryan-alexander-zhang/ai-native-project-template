---
id: record-00003-whiteboard-edge-emphasis-acceptance
type: record
status: active
parent: plan-00004-whiteboard-edge-emphasis
verifies: [spec-00001-FR-28, spec-00001-FR-29, spec-00001-FR-30]
---

# 验收记录：边的弱化/强调与关系列表

对 [plan-00004-whiteboard-edge-emphasis](../plan/plan-00004-whiteboard-edge-emphasis.md)
的验收。取舍见
[decision-00003-whiteboard-edge-emphasis](../decision/decision-00003-whiteboard-edge-emphasis.md)。

- 套件：`cd tools/whiteboard && npm test` → **19 个测试文件、367 个测试全部通过**，
  无未处理异常
- 覆盖率：语句 98.85%、分支 95.42%、函数 97.54%、行 99.26%（门槛 90%；排除名单
  未加宽）。`canvasModel.ts` 语句/函数/行 100%、分支 98.07%
- 类型检查：`npm run typecheck` 无错误；`npm run build` 通过

测试名为 `tools/whiteboard/` 下的用例标题：`w/` = `web/test/`。

## 新增 GWT

| GWT id | 测试 | 结果 |
| --- | --- | --- |
| spec-00001-AC-28.1 | draws an unselected edge dim and unlabelled (w/canvas)；shows no edge label until a node is selected (w/canvas)；draws every edge dim and unlabelled on a graph with several (w/canvas) | pass |
| spec-00001-AC-28.2 | draws an unselected edge dim and unlabelled (w/canvas) —— 单边图同样是弱化态 | pass |
| spec-00001-AC-28.3 | draws nothing when no document declares a relation (w/canvas) | pass |
| spec-00001-AC-28.4 | merges two relations declared between the same pair (w/canvas) | pass |
| spec-00001-AC-29.1 | emphasises and labels the edges of the selected node (w/canvas)；labels the selected node edges and drops the labels again on deselect (w/canvas) | pass |
| spec-00001-AC-29.2 | suppresses the edges that have nothing to do with the selection (w/canvas)；suppresses every node that does not share an edge with the selection (w/canvas)；recedes when suppressed (w/board)；**recedes the nodes that have nothing to do with the selection (w/canvas)** —— 板级接线，经变异检验：把 `Board.tsx` 的 `suppressed` 改成常量 `false` 会让它失败 | pass |
| spec-00001-AC-29.3 | labels the selected node edges and drops the labels again on deselect (w/canvas)；suppresses nothing when there is no selection (w/canvas) | pass |
| spec-00001-AC-29.4 | emphasises only the newly selected node edges (w/canvas) | pass |
| spec-00001-AC-29.5 | emphasises nothing when the selected document has no relations (w/canvas) | pass |
| spec-00001-AC-29.6 | keeps the emphasis through a refresh (w/canvas) | pass |
| spec-00001-AC-29.7 | emphasises the edges of a document chosen in the command palette (w/canvas) | pass |
| spec-00001-AC-29.8 | keeps an emphasised edge marked when it points at a ghost (w/canvas) | pass |
| spec-00001-AC-30.1 | lists every relation with the field, the direction, and the other end (w/canvas)；lists the relations of the selected node and jumps to the one picked (w/canvas) | pass |
| spec-00001-AC-30.2 | states which end declared each relation (w/toolbar) | pass |
| spec-00001-AC-30.3 | hands back the document picked from the list (w/toolbar)；lists the relations of the selected node and jumps to the one picked (w/canvas) | pass |
| spec-00001-AC-30.4 | says there are no relations rather than showing an empty list (w/toolbar) | pass |
| spec-00001-AC-30.5 | marks a relation whose target does not exist (w/toolbar)；lists a relation whose target does not exist, and marks it (w/canvas) | pass |
| spec-00001-AC-30.6 | refuses to jump to a relation whose document does not exist (w/canvas) | pass |

## 被本次修订的既有 GWT

| GWT id | 变化 |
| --- | --- |
| spec-00001-AC-2.4 | 异常节点的工具栏从「只有编辑」改为「编辑 + 关系列表」——列出断链指向了谁，正是修复它所需要的读取动作，且不改动任何文档。测试更名为 `offers only the editor and the relation list for a document with front matter problems` |
| spec-00001-AC-1.1 | **已修订**。原文「每个关系字段呈现为一条边」被同向多字段合并（AC-28.4）证伪，而本仓恰有一例（`decision-00002 → spec-00001`）。核验指出：record 不能改 spec。现已在 spec 内改为「每个关系字段都出现在图上」，并在 FR-28 写明它改变了边的条数 |

## 实测：本仓真实文档

`readGraph → layoutGraph → toFlowEdges` 跑本仓 19 份文档、39 条边：

| 视图 | 画布上的标签 | 列表行数 | 被压弱的边 |
| --- | --- | --- | --- |
| 默认（无选中） | **0** | — | — |
| 选中 `spec-00001`（中枢） | 16 | 17 | 23 |
| 选中 `design-00002` | 8 | 8 | 31 |
| 选中 `idea-00001` | 1 | 1 | 38 |

同向多字段合并在真实数据上命中一处：`decision-00002 → spec-00001` 的
`motivated_by · constrains` 合成一条边。

**默认视图的目标达成了**：39 个常驻标签降为 0，图上只剩淡线与节点。

## decision-00003 §5 列出的两条未验证前提

plan 验收路径第 5 条要求验证它们，不得默认通过。结论：

1. **「17 行的关系列表读得动」——未验证。** 列表已按出/入向与字段序分组，且
   `max-h-80` 可滚动，但**没有在浏览器里看过**。这属于「看着对不对」的判断，
   模型层与 jsdom 都答不了。
2. **「选中中枢时的标签不再叠字」——部分证伪，需留意。** 中枢选中时画布上仍有
   **16 个标签**同时出现。比原先的 39 个好，且它们分散在 16 条通往不同列的边的
   中点上、不是挤在一处；但这仍是本次改动**没有解决**的密度，只是把它从「永远
   如此」降为「只在选中中枢时如此」。关系列表是这种情况下的实际出路。
   若日后判定仍不可读，下一步是给标签加密度阈值（超过 N 条时只在悬停的那条上
   显示），而不是回到走线——理由见 decision-00003 §4。

## 未覆盖与已知缺口

- **未做真实浏览器验收**：上面两条都需要它。plan 验收路径第 5 条因此**未完全
  满足**，而第 6 条写明「任何 gap 阻塞 `resolved`」——**故 `plan-00004` 保持
  `open`**，等一次人工开板确认。这不是形式主义：第 2 条前提已经部分证伪，正是
  需要人眼的那一类。
- **反向的同向合并不适用**：`A→B` 与 `B→A` 两条边几何上也重合，但箭头相反，故
  不合并。本仓无此情形，无测试覆盖。
- **一次未复现的失败**：改动过程中有一次全量运行报 1 条失败，随后连续多次全量
  与单文件运行均通过，未能复现，也未捕获到失败详情。记此备查——若再出现，先查
  `keeps the emphasis through a refresh`（它等待第二次 `api.graph` 调用，是本次
  新增用例里唯一有时序依赖的一条）。核验独立跑了三轮亦未复现。
- **`record-00003` 自身让白板多出三条断链**：本文的 `verifies` 写的是 requirement
  id（`docs/record/README.md` 明确许可，plan E5 也这么要求），而白板的图模型把每个
  关系目标都当作**文档 id**，于是这三项成了断链边，顶栏从此显示「3 issues」。这是
  文档约定与工具数据模型之间的真实冲突，需单独裁定，记在
  `issue-00005` §8 的 Residual state。本记录测得的那张表是在排除本文件后跑的
  19 份文档口径。
