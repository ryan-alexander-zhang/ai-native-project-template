---
id: plan-00017-whiteboard-inline-id-navigation
type: plan
status: open
implements: [spec-00001-FR-57, spec-00001-FR-58, spec-00001-FR-59]
---

# Plan: 行内 id 跳转——正文引用一键到节点（第十五轮）

对 `spec-00001-FR-57` … `FR-59` 的实现：行内呈现处正文中恰为一个可解析 id
的反引号行内代码可激活（单击与 Enter 同权），回到顶层白板并定位、选中该 id
所属的文档节点；不可解析者不可点击，一切只作用于呈现层。

## Design

- 界面侧：[design-00002-whiteboard-ui](../design/design-00002-whiteboard-ui.md)
  §9「行内 id 跳转（第十五轮）」。
- 载荷契约（`idOwners` 表）：
  [design-00001-docs-whiteboard](../design/design-00001-docs-whiteboard.md)
  §7 的第十五轮增补。

## Tasks

- **T1 — 服务端 `idOwners` 表**：按 design-00001 §7 的构建规则加入 graph
  载荷（ok 节点按节点键自映射、条目/AC id 映射到所属文档；撞 id 与异常
  文档的条目不入表，不经 `declaredId()`）；不改变边与诊断的推导
  （`spec-00001-AC-59.1` 的守卫）。
- **T2 — InlineMarkdown 的 `code` 映射**：按 design-00002 §9 新增映射与
  按钮样态；回调 prop 未传时行为与现状完全一致（既有 `inline.test.tsx`
  断言不动）。
- **T3 — 通路接线**：`focus` 接到九个渲染点（Details / Inspector 沿 props，
  SubNodes 经节点 `data` 或 context）；click 与 keydown 两条路径
  `stopPropagation`；退出子画布与清详情面板沿用 `focus` 既有行为，但把
  「目标在图上」的判定挪到视图清空之前——不合法原地拒绝提示、视图不动
  （就近关闭，`spec-00001-AC-57.8`）。
- **T4 — 测试**：按 `spec-00001-AC-57.1` … `AC-57.8`、`AC-58.1` …
  `AC-58.5`、`AC-59.1` … `AC-59.3` 各落一测，每测带 `// <AC id>` 溯源
  标注（沿 plan-00016 T2 的约定）；`AC-59.1` 的夹具为「正文引另一文档
  条目 id、两文档间无 front matter 关系」的最小仓。
- **T5 — 验收收口**：`npm test`、typecheck、覆盖率门不降；写 record
  （`parent` 指向本 plan，`verifies: [spec-00001-FR-57, spec-00001-FR-58,
  spec-00001-FR-59]`）逐 AC 列行，以本 plan 过 resolved 门收口。

## Detailed Acceptance Path

1. T1 落地 → verify: T4 夹具下载荷含 `idOwners`，边数与诊断计数与不含
   该表时的既有断言一致。
2. T2/T3 落地 → verify: AC-57.1…57.8、AC-58.1…58.5、AC-59.2…59.3 对应
   测试通过。
3. 全量测试、typecheck、覆盖率门全绿 → verify: 命令退出码与阈值。
4. record 列全三条 FR 的十六条 AC，本 plan 经 `open → resolved` 放行 →
   verify: resolved 门通过。

## Out of Scope

- 条目级跳转（进子画布定位条目节点）与编辑器预览中的跳转——spec-00001
  §6 第十五轮已列为范围外。
- 明文散文中 id 的识别——只认反引号行内代码（decision-00005 §4 约定，
  `spec-00001-FR-58` 持有不识别的行为）。
