---
id: record-00004-whiteboard-requirement-panel-acceptance
type: record
status: active
parent: plan-00005-whiteboard-requirement-panel
verifies: [spec-00001-AC-2.5, spec-00001-AC-2.6, spec-00001-AC-28.5, spec-00001-FR-31, spec-00001-FR-32, spec-00001-FR-33, spec-00001-FR-34, spec-00001-FR-35, spec-00001-FR-36]
---

# 验收记录：检视面板、覆盖状态与子画布

对 [plan-00005-whiteboard-requirement-panel](../plan/plan-00005-whiteboard-requirement-panel.md)
的验收。取舍见
[decision-00004-whiteboard-requirement-panel](../decision/decision-00004-whiteboard-requirement-panel.md)。

- 套件：`cd tools/whiteboard && npm test` → **22 个测试文件、443 个测试全部通过**
  （plan-00004 验收时为 367）
- 覆盖率：语句 99.11%、分支 95.62%、函数 98.33%、行 99.53%（门槛 90%；排除名单
  未加宽）。新增源文件（`requirements.ts`、`Inspector.tsx`、`subCanvas.ts`、
  `SubNodes.tsx`、`coverageMarks.ts`）均为 100% 行
- 类型检查：`npm run typecheck` 无错误；`npm run build` 通过
- GWT 逐条核验由未参与实现的 subagent 完成：39/39 有对应通过的测试，抽查断言
  非恒真（含「hover 无边时不变」的用例先证明基线会变），FR 层无 unverified

测试名为 `tools/whiteboard/` 下的用例标题：`t/` = `test/`，`w/` = `web/test/`。

## 新增 GWT

| GWT id | 测试 | 结果 |
| --- | --- | --- |
| spec-00001-AC-2.5 | lands a relation naming a requirement item on the document that declares it (t/docRepository)；draws an edge naming requirement items to the document holding them, unmarked (w/canvas) | pass |
| spec-00001-AC-2.6 | marks a relation naming an item that does not exist (t/docRepository)；lists each declared item id and jumps to the document holding it (w/toolbar) | pass |
| spec-00001-AC-28.5 | merges the ids of one field that land on the same document into a single edge (t/docRepository)；lists each declared item id of a merged edge, pointing at the document holding it (w/canvas) | pass |
| spec-00001-AC-31.1 | lists the items of the selected spec with id, text and AC count (w/inspector) | pass |
| spec-00001-AC-31.2 | lists an item declared in a decision table beside one declared as a list entry (w/inspector) | pass |
| spec-00001-AC-31.3 | shows no panel for a document type that declares no items (w/inspector) | pass |
| spec-00001-AC-31.4 | says there are no items rather than showing an empty panel (w/inspector) | pass |
| spec-00001-AC-31.5 | puts one node on the canvas per document, whatever the items say (w/inspector) | pass |
| spec-00001-AC-31.6 | closes the panel when the selection is dropped (w/inspector) | pass |
| spec-00001-AC-31.7 | opens the panel for a spec whose front matter is broken (w/inspector) | pass |
| spec-00001-AC-31.8 | leaves the editor in place when a spec is selected (w/inspector) | pass |
| spec-00001-AC-31.9 | shows the panel as soon as the editor gives the slot back (w/inspector) | pass |
| spec-00001-AC-32.1 | calls an item verified when every criterion has a passing row (t/requirements)；端到端同名场景 (t/server) | pass |
| spec-00001-AC-32.2 | calls an item uncovered when no row references any of its criteria (t/requirements) | pass |
| spec-00001-AC-32.3 | calls an item uncovered when only one of its two criteria has a row (t/requirements) | pass |
| spec-00001-AC-32.4 | calls an item failing when a row is n/a even though every criterion passed (t/requirements) | pass |
| spec-00001-AC-32.5 | counts a row from a draft record (t/server) | pass |
| spec-00001-AC-32.6 | carries each coverage state as a named icon (w/inspector)；carries a coverage state on every item (t/requirements) | pass |
| spec-00001-AC-32.7 | calls an item with no criteria at all uncovered (t/requirements) | pass |
| spec-00001-AC-32.8 | calls an item failing when a row failed and another criterion has no row (t/requirements) | pass |
| spec-00001-AC-32.9 | calls an item failing when a row naming the item itself is n/a (t/requirements) | pass |
| spec-00001-AC-32.10 | calls an item uncovered when only the item itself has a passing row (t/requirements) | pass |
| spec-00001-AC-33.1 | lists a row that names an id no item holds (t/requirements、t/server、w/inspector) | pass |
| spec-00001-AC-33.2 | keeps the stray row out of coverage (t/requirements)；keeps a document sound (t/docRepository) | pass |
| spec-00001-AC-33.3 | lists a criterion attributed to an item that does not exist, and leaves it uncounted (t/requirements、w/inspector) | pass |
| spec-00001-AC-34.1 | emphasises the edge to the record that verified the item, labelled with the cited AC ids (w/inspector) | pass |
| spec-00001-AC-34.2 | gives the selected-state presentation back when the pointer leaves (w/inspector) | pass |
| spec-00001-AC-34.3 | emphasises nothing extra for an uncovered item (w/inspector) | pass |
| spec-00001-AC-34.4 | emphasises the same edge when the row takes keyboard focus (w/inspector) | pass |
| spec-00001-AC-34.5 | emphasises nothing when the verifying record shares no edge with the document (w/inspector) | pass |
| spec-00001-AC-34.6 | emphasises both edges when two records verified the item (w/inspector) | pass |
| spec-00001-AC-35.1 | replaces the document nodes with the items, criteria and acceptance rows (w/subcanvas) | pass |
| spec-00001-AC-35.2 | links item to criterion to acceptance row, and says which record ran which test (w/subcanvas) | pass |
| spec-00001-AC-35.3 | marks an uncovered item on its node (w/subcanvas) | pass |
| spec-00001-AC-35.4 | shows the trail «Board / \<document id\>» in the header (w/subcanvas) | pass |
| spec-00001-AC-35.5 | offers no way down for a document with no items (w/subcanvas) | pass |
| spec-00001-AC-35.6 | shows no breadcrumb on the top-level board (w/subcanvas) | pass |
| spec-00001-AC-36.1 | returns to the board on the document, selected and in view (w/subcanvas) | pass |
| spec-00001-AC-36.2 | brings the panel back with it, as a direct selection would (w/subcanvas) | pass |

无 fail / missing 行；范围内每条 FR 的全部 AC 均被上表引用。

## 既有断言的预期更新（非回归）

按 plan-00005 验收路径第 3 条：edge 契约增加 `declaredTargets` 后，
`t/docRepository`（3 处）、`t/acceptance`（2 处）与 `w/board`、`w/canvas` 的
夹具改用 `relationEdge()` 工厂（3(a)）；`w/toolbar` 与 `w/canvas` 的关系列表
夹具增加 `targetId`（3(b)）；3(c) 无需动作——`AC-29.8` 的夹具本就指向真正
无法解析的 id。既有断言语义无一削弱。

## 实测核对（plan 验收路径第 5 条）

用本仓真实文档（23 节点、52 边）实跑，浏览器自动化逐项验证并截图留证：

- **(a) 36 条 FR 的面板可读——通过**（decision-00004 §4 未验前提一）。三屏高度
  原生滚动，79 帧连续滚动中位 16.7ms、掉帧 0；id 对比度 19.9:1、正文 4.83:1
  （过 AA）。
- **(b) 标签切换无可感抖动——通过**（未验前提二）。悬停前后边的 path 不变、
  标签中心点逐像素不动（只有宽度对称变化）、transition 为 0s 瞬时替换、连续
  跨行悬停 longtask 为 0。
- **(c) 覆盖缺口一眼可见——通过**。spec-00001：36 条目 = 28 已验证 + 8 未覆盖
  （FR-2、FR-28、FR-31…FR-36——前两条因本轮新增 AC-2.5/2.6、AC-28.5 尚无
  验收行，属预期；后六条正是本 plan 交付的 FR，由本记录的上表补齐证据）。
  rule-00001：19 条目 = 18 已验证 + 1 未通过（BR-19，record-00001 记 `n/a`，
  口径即 decision-00004 §5）。UI 读数与 `/api/docs/:id/items` 完全一致。
  注：decision-00004 §1 的「BR-3…BR-9 共 7 条缺口」经 record-00001 区间行展开
  后，严格口径下已归零——决策表行形态（BR-2…BR-9）解析正常。
- **(d) 子画布可辨——通过**。三列结构清楚，未覆盖条目带虚线圈且第三列整列
  缺失，断口一眼可辨；面包屑仅子画布出现，返回后 spec-00001 选中、在视口内、
  面板恢复。
- 顶栏异常计数 0（`issues: []`）；悬停 FR-28/FR-29 的边标签 AC id 与
  record-00003 清单逐项一致。

## 实测发现的观察项（不阻塞，留待后续裁定）

1. 面板条目正文按原始 Markdown 直出（`**…**`、反引号可见），读得动但有噪声。
2. 悬停多 AC 条目时标签可拼到 900px+，绘于节点之上会遮住途经卡片的 id 行；
   0.5 缩放下标签字号约 5px，需放大才可读（1.0 缩放清晰）。decision-00003 §5
   预留的「标签密度阈值」在此重新变得相关。
3. 进入子画布时初始视口未 fit 到全图，需手动缩放。
4. 从子画布返回后，浮窗工具栏右端可被检视面板左缘裁掉一截。
5. `web/src/canvasModel.ts` 含字面 NUL 字节（合并键分隔符），git 视整个文件为
   二进制，diff 不可读——一字符可修，未在本 plan 范围内动。
6. 根 `.gitignore` 的 `coverage.*` 规则会吞掉任何名为 `coverage.ts` 的源文件
   （T4 因此把新模块命名为 `coverageMarks.ts` 绕开）。
7. 仓库根部没有 `npm run whiteboard` 代理脚本，design-00001 §8 写有——文档与
   现状不符，早于本 plan 存在。

## 结论

39/39 GWT 通过、无 unverified 条目、覆盖率达标、四项实测通过、
issue-00005 §8 残留已消除（同步销记）。plan-00005 置 `resolved`。
