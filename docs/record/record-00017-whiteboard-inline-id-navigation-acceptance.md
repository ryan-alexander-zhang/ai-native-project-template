---
id: record-00017-whiteboard-inline-id-navigation-acceptance
type: record
status: active
parent: plan-00017-whiteboard-inline-id-navigation
verifies: [spec-00001-FR-57, spec-00001-FR-58, spec-00001-FR-59]
---

# 验收记录：行内 id 跳转

对 [plan-00017-whiteboard-inline-id-navigation](../plan/plan-00017-whiteboard-inline-id-navigation.md)
的验收。本轮交付 `spec-00001-FR-57` … `FR-59`：服务端在 graph 载荷加
`idOwners` 表（design-00001 §7），前端 `InlineMarkdown` 新增 `code` 元素映射
把恰为一个可解析 id 的行内代码渲染为跳转按钮，回调经 props（Details /
Inspector）与 context（SubNodes）接到 Board 的 `focus`；`focus` 的「目标在
图上」判定挪到视图清空之前（就近关闭）。清单按 AC 逐条列全三条 FR 的十六条
AC。测试路径相对 `tools/whiteboard/`。

## 质量门

- `npm test`：38 个文件、976 个测试全部通过。
- `npm run typecheck`：`tsc --noEmit` 无错。
- `npm run test:coverage`：statements 99.15% / branches 95.25% /
  functions 98.67% / lines 99.62%，四项均高于 90% 门槛且不低于改动前，
  未调整任何阈值。

## 验收清单

| 被验 id | 测试 | 结果 |
| --- | --- | --- |
| spec-00001-AC-57.1 | jumps from the detail panel to the document owning the item id (web/test/idJump.test.tsx) | pass |
| spec-00001-AC-57.2 | jumps from the expanded row to the document the id names (web/test/idJump.test.tsx) | pass |
| spec-00001-AC-57.3 | jumps from a sub-canvas node rather than opening the detail panel (web/test/idJump.test.tsx) | pass |
| spec-00001-AC-57.4 | jumps from the truncated row without expanding it (web/test/idJump.test.tsx) | pass |
| spec-00001-AC-57.5 | jumps on Enter exactly as on click, leaving the expansion state alone (web/test/idJump.test.tsx) | pass |
| spec-00001-AC-57.6 | returns to the top board with the same document selected on a self-reference (web/test/idJump.test.tsx) | pass |
| spec-00001-AC-57.7 | hands the right slot to the target document's inspector (web/test/idJump.test.tsx) | pass |
| spec-00001-AC-57.8 | refuses a jump whose document has left the board and moves nothing (web/test/idJump.test.tsx) | pass |
| spec-00001-AC-58.1 | leaves an unresolvable id inert (web/test/idJump.test.tsx) | pass |
| spec-00001-AC-58.2 | renders an unresolvable id as plain inline code with no mark (web/test/idJump.test.tsx) | pass |
| spec-00001-AC-58.3 | makes nothing clickable of an id outside backticks (web/test/idJump.test.tsx) | pass |
| spec-00001-AC-58.4 | makes nothing clickable of a span that is more than the id (web/test/idJump.test.tsx) | pass |
| spec-00001-AC-58.5 | keeps a colliding id and the items behind it out of the table (test/docRepository.test.ts) | pass |
| spec-00001-AC-59.1 | feeds no edge and no diagnostic for an id referenced in prose (test/docRepository.test.ts) | pass |
| spec-00001-AC-59.2 | underlines the clickable span and not its plain neighbour (web/test/idJump.test.tsx) | pass |
| spec-00001-AC-59.3 | puts no anchor on the page (web/test/idJump.test.tsx) | pass |

交付范围内没有未覆盖或未通过的条目。FR-57 的两条正文口径另有支撑测试：
maps document ids to themselves and item and AC ids to their document、
keeps an anomalous document reachable while its items stay unclaimed
（test/docRepository.test.ts）。

## 实现期的既定取舍

- `DocGraph.idOwners` 设为必填字段而非可选：契约收紧，测试夹具随
  typecheck 逐一补 `idOwners: {}`，不给「载荷缺表」留静默路径。
- 按钮内包一层 `code` 元素：可点击 id 保持行内代码的字形，AC-59.2 的可辨
  维度由按钮的下划线承载。
- `InlineMarkdown` 剩两个未覆盖分支均为不可达的防御分支（`code` 子节点非
  字符串、AST 节点无 position），不为凑数删守卫；文件分支覆盖 80%，全局
  分支门 95.25% 高于改动前。
- AC-57.5 与 AC-57.4 在 Inspector 组件级验证（跳转后检视面板整体切换，
  「所在行展开态不变」在板级不再可观察）；跳转与右槽归属由板级用例
  （AC-57.1/57.2/57.7）覆盖。
