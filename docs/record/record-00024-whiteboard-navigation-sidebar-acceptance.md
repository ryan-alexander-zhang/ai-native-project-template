---
id: record-00024-whiteboard-navigation-sidebar-acceptance
type: record
status: active
parent: plan-00024-whiteboard-navigation-sidebar
verifies: [spec-00008-whiteboard-navigation-sidebar]
---

# 验收记录：导航栏与缩略图

对 [plan-00024-whiteboard-navigation-sidebar](../plan/plan-00024-whiteboard-navigation-sidebar.md)
的验收。交付范围为 `spec-00008` 全部 8 条 FR 的 32 条 AC，逐条各落一测；纯页面
改动（`tools/whiteboard/web/`），零服务端改动；design-00002 第二十四轮修订轮
（T1）先行接收，两处落地据实校正见「实现期的既定取舍」。代码由 Opus 子代理
编写，独立子代理逐条核对 AC 与测试后放行。测试路径相对 `tools/whiteboard/`。

## 质量门

- `npm test`：56 个文件、1692 个测试全部通过（55 / 1660 基线 + 本轮 1 个新文件
  32 条）。
- `npm run typecheck`：`tsc --noEmit` 无错。
- `npm run test:coverage`：statements 98.64% / branches 95.35% / functions 98.44%
  / lines 99.20%，四项均高于 90% 门槛且不低于改动前（98.62 / 95.27 基线），
  未调整任何阈值或排除项；新增四个模块均 100%。
- `npm run build`：通过。
- 实机验证：`npm start` 后浏览器打开白板，导航栏缺省展开、按类型组列出 105 份
  文档；点行定位并高亮；收起后重现；缩略图 105 个色块按状态着色。

## 验收清单

| 被验 id | 测试 | 结果 |
| --- | --- | --- |
| spec-00008-AC-1.1 | groups every document by type in column order, each group in row order (web/test/sidebar.test.tsx) | pass |
| spec-00008-AC-1.2 | puts an undeclared type and then the documents without one after the declared ones (web/test/sidebar.test.tsx) | pass |
| spec-00008-AC-1.3 | gives each half of a collision its own row, by path, beside the id they collide on (web/test/sidebar.test.tsx) | pass |
| spec-00008-AC-1.4 | says «front matter problem» where an anomalous document's status would be (web/test/sidebar.test.tsx) | pass |
| spec-00008-AC-1.5 | holds no group at all when no document is on the board (web/test/sidebar.test.tsx) | pass |
| spec-00008-AC-2.1 | selects the document of the row and centres the viewport on it (web/test/sidebar.test.tsx) | pass |
| spec-00008-AC-2.2 | leaves a sub-canvas and its detail behind on the way (web/test/sidebar.test.tsx) | pass |
| spec-00008-AC-2.3 | centres again on the row already selected (web/test/sidebar.test.tsx) | pass |
| spec-00008-AC-3.1 | highlights the row of the node picked on the canvas and scrolls it into view (web/test/sidebar.test.tsx) | pass |
| spec-00008-AC-3.2 | opens the collapsed group the jumped-to document sits in (web/test/sidebar.test.tsx) | pass |
| spec-00008-AC-3.3 | highlights nothing once the selection is dropped (web/test/sidebar.test.tsx) | pass |
| spec-00008-AC-3.4 | catches up on the selection when it is brought back (web/test/sidebar.test.tsx) | pass |
| spec-00008-AC-4.1 | puts the rows away and keeps the header and its count (web/test/sidebar.test.tsx) | pass |
| spec-00008-AC-4.2 | is still collapsed the next time the board is opened (web/test/sidebar.test.tsx) | pass |
| spec-00008-AC-4.3 | opens every group when none was ever collapsed (web/test/sidebar.test.tsx) | pass |
| spec-00008-AC-4.4 | brings the rows back on the next press (web/test/sidebar.test.tsx) | pass |
| spec-00008-AC-4.5 | stays collapsed when the group is the selected row's own (web/test/sidebar.test.tsx) | pass |
| spec-00008-AC-5.1 | opens the board with the sidebar on show (web/test/sidebar.test.tsx) | pass |
| spec-00008-AC-5.2 | puts the sidebar away when it is pressed (web/test/sidebar.test.tsx) | pass |
| spec-00008-AC-5.3 | leaves the sidebar away the next time the board is opened (web/test/sidebar.test.tsx) | pass |
| spec-00008-AC-5.4 | brings the sidebar back on the next press (web/test/sidebar.test.tsx) | pass |
| spec-00008-AC-6.1 | takes a new document into its group at its row (web/test/sidebar.test.tsx) | pass |
| spec-00008-AC-6.2 | keeps the collapsed groups collapsed and the selected row highlighted (web/test/sidebar.test.tsx) | pass |
| spec-00008-AC-6.3 | drops the row and the highlight with the document the selection was on (web/test/sidebar.test.tsx) | pass |
| spec-00008-AC-6.4 | drops a group with its last row (web/test/sidebar.test.tsx) | pass |
| spec-00008-AC-6.5 | follows a status the refresh changed (web/test/sidebar.test.tsx) | pass |
| spec-00008-AC-6.6 | opens a new group in its column place (web/test/sidebar.test.tsx) | pass |
| spec-00008-AC-7.1 | draws a block per node, coloured by its status and by the anomaly colour (web/test/canvas.test.tsx) | pass |
| spec-00008-AC-7.2 | shows the sub-canvas's nodes once the board has drilled into one (web/test/canvas.test.tsx) | pass |
| spec-00008-AC-7.3 | is still drawn, and empty, when no document is on the board (web/test/canvas.test.tsx) | pass |
| spec-00008-AC-8.1 | refuses in place and moves neither the selection nor the viewport (web/test/sidebar.test.tsx) | pass |
| spec-00008-AC-8.2 | refuses the same way the second time (web/test/sidebar.test.tsx) | pass |

无未覆盖或未通过条目。

## 实现期的既定取舍

- **归组抽成一个函数而非导出一对**：design-00002 §17.2 初稿写「导出 `columnKey`
  与 `byIdThenPath`」，落地改为 `layout.ts` 导出 `orderedColumns`，
  `layoutGraph` 与 `typeGroups` 都是它的映射——导出一对函数会让分桶循环在
  两处各写一遍，重复正是质量门要挡的。§17.2 已据实校正。
- **文档节点声明尺寸**：缩略块尺寸取自节点自带 `width`/`height`，本板是不接
  `onNodesChange` 的受控图，量出的尺寸不回写——不声明则缩略图一个块也没有。
  `toFlowNodes` 给每个节点声明 `NODE_WIDTH × NODE_HEIGHT`，与 `subCanvas` 同
  做法。§17.4 已据实增补。
- **测试桩两处（`web/test/setup.ts`）**：外层多一个面板后，react-resizable-panels
  的分隔条命中检测在 jsdom 的零尺寸盒模型下把每次按下都当成按在分隔条上并
  `preventDefault()`，Radix 菜单因此不再打开——桩给面板报一个真实盒；由此暴露
  user-event 构造的事件 `view` 为 null 而 d3-drag 读 `event.view.document` 的
  既有缺口——桩跳过 user-event 对 `view` 的那一次 `defineProperty`。两处均不
  削弱任何断言，既有测试一行未改。
- **AC-2.3 的 Given**「拖离视口」是 React Flow 的内部视口状态，jsdom 无法移动；
  测试在拖动应发生处清空 `setCenter` 记录，观测重居中调用本身。
- **发现两处既有缺陷，未在本轮修**（按仓库规则需先立 issue）：`NodeCard` 内的
  交互控件未带 `nodrag` 类，按下即启动节点拖动手势；本板受控图不接
  `onNodesChange`，React Flow 的 `measured` 尺寸永不回写节点。两者均已向域主
  报告。
