---
id: record-00025-whiteboard-sidebar-collapsed-default-acceptance
type: record
status: active
parent: plan-00024-whiteboard-navigation-sidebar
verifies: [spec-00008-FR-1, spec-00008-FR-3, spec-00008-FR-4, spec-00008-FR-6]
---

# 验收记录：类型组缺省折叠（spec-00008 第二十五轮）

对 [plan-00024-whiteboard-navigation-sidebar](../plan/plan-00024-whiteboard-navigation-sidebar.md)
的补充验收。`spec-00008` 第二十五轮修订轮把 FR-4 改为类型组缺省折叠、持久化
展开态，AC-4.1 … AC-4.5 与 AC-3.4 改写、AC-6.2 拆出 AC-6.7、AC-1.1/1.3/1.4 的
Given 补「所在组已展开」。本记录覆盖这十二条改动过的 AC；其余 21 条仍以
[record-00024](record-00024-whiteboard-navigation-sidebar-acceptance.md) 为证据
（其 AC-4.x 与 AC-6.2 行验的是旧语义，已由本记录取代）。改动限于
`web/src/sidebar.ts`、`web/src/Sidebar.tsx` 与 `web/test/sidebar.test.tsx`。
测试路径相对 `tools/whiteboard/`。

## 质量门

- `npm test`：56 个文件、1693 个测试全部通过（上一轮 1692 + AC-6.7 一条）。
- `npm run typecheck`：`tsc --noEmit` 无错。
- `npm run test:coverage`：statements 98.64% / branches 95.35% / functions 98.43%
  / lines 99.20%，四项均高于 90% 门槛，未调整任何阈值。
- `npm run build`：通过。
- 实机验证：清空浏览器本地存储后打开白板，导航栏只呈现类型组目录与计数。

## 验收清单

| 被验 id | 测试 | 结果 |
| --- | --- | --- |
| spec-00008-AC-1.1 | groups every document by type in column order, each group in row order (web/test/sidebar.test.tsx) | pass |
| spec-00008-AC-1.3 | gives each half of a collision its own row, by path, beside the id they collide on (web/test/sidebar.test.tsx) | pass |
| spec-00008-AC-1.4 | says «front matter problem» where an anomalous document's status would be (web/test/sidebar.test.tsx) | pass |
| spec-00008-AC-3.4 | catches up on the selection when it is brought back (web/test/sidebar.test.tsx) | pass |
| spec-00008-AC-4.1 | brings the rows out and keeps the header and its count (web/test/sidebar.test.tsx) | pass |
| spec-00008-AC-4.2 | is still expanded the next time the board is opened, alone (web/test/sidebar.test.tsx) | pass |
| spec-00008-AC-4.3 | collapses every group when none was ever expanded (web/test/sidebar.test.tsx) | pass |
| spec-00008-AC-4.4 | puts the rows away on the next press and keeps the header and its count (web/test/sidebar.test.tsx) | pass |
| spec-00008-AC-4.5 | stays collapsed when the group is the selected row's own (web/test/sidebar.test.tsx) | pass |
| spec-00008-AC-6.2 | keeps the expanded group expanded and the collapsed one collapsed (web/test/sidebar.test.tsx) | pass |
| spec-00008-AC-6.7 | keeps the selected row highlighted (web/test/sidebar.test.tsx) | pass |

无未覆盖或未通过条目。

## 实现期的既定取舍

- 旧键 `whiteboard-sidebar-collapsed` 不读、不迁移、不清理（design-00002 §17.1）。
- AC-6.6 的新组无法在 Given 里预先展开，测试改为断言整条导航栏的组头序列与
  计数，而非行。
