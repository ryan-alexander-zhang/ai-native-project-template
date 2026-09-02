---
id: plan-00024-whiteboard-navigation-sidebar
type: plan
status: resolved
implements: [spec-00008-whiteboard-navigation-sidebar]
---

# Plan: 导航栏与缩略图

> 落地 `spec-00008` 全部八条 FR 与 `decision-00016` 的各项裁决：纯页面
> （`tools/whiteboard/web/`）改动，零服务端改动；含 design-00002 的第二十四轮
> 修订轮。

## Design

Links only：

- [design-00002-whiteboard-ui](../design/design-00002-whiteboard-ui.md) —— §2
  布局（左侧区域）；§17 导航栏的停靠与持久化（17.1）、内容构造（17.2）、
  与选中的联动（17.3）、缩略图（17.4）、可访问性与测试影响（17.5、17.6）。

## Tasks

T1 是文档轮，先于一切代码；T2 独立；T3 依赖 T2；T4 独立于 T2/T3；T5 收口。
**`decision-00016` 与 `spec-00008` 转 `active`、design-00002 重新接收之前，
不得开写 T2 … T4 的代码。**

- **T1 — design-00002 修订轮收口**：本轮已落笔（§2 布局图、§17、§18、
  `informs`）；余下审计余项修正 → 接收。
- **T2 — 归组模型** (spec-00008-FR-1)：`web/src/layout.ts` 与新增的
  `web/src/sidebarModel.ts`，按 design-00002 §17.2。
- **T3 — 导航栏组件与接线** (spec-00008-FR-1 … FR-6, FR-8)：新增
  `web/src/Sidebar.tsx` 与 `web/src/sidebar.ts`（开合态与折叠态的本地持久化），
  `Board.tsx` 接线（外层面板组、顶栏开关、行点击接 `focus`），按 design-00002
  §17.1 … §17.3。
- **T4 — 缩略图** (spec-00008-FR-7)：`Board.tsx` 与 `web/src/index.css`，按
  design-00002 §17.4。
- **T5 — 测试与验收收口**：新增 `web/test/sidebar.test.tsx`，缩略图用例并入
  `web/test/canvas.test.tsx`；按 `spec-00008-AC-1.1` … `AC-8.2` 各落一测，
  每测带 `// <AC id>` 溯源标注；既有测试回归（布局改动会影响
  `panels.test.tsx`、`viewport.test.tsx`、`focus.test.tsx` 的挂载）；
  `npm test`、`npm run typecheck`、覆盖率门不降；写 record（`parent` 指向
  本 plan，`verifies: [spec-00008-whiteboard-navigation-sidebar]`）；
  `tools/whiteboard/README.md` 如需增补一行导航栏说明一并做。

## Detailed Acceptance Path

1. `decision-00016`、`spec-00008` 转 `active`，T1 完成 → verify: 三份文档均
   `active`，design-00002 含 §17；`CONTEXT.md` 随 spec 接收含「导航栏」「类型组」
   「缩略图」三词条且「呈现状态」枚举已增导航栏开合与折叠态。
2. T2 … T4 落地 → verify: `spec-00008-AC-1.1` … `AC-8.2` 对应测试全部通过。
3. 全量测试、typecheck、覆盖率门全绿 → verify: 三个命令退出码为 0，四个覆盖率
   数字不低于阈值，无门槛下调。
4. 手工验证：`npm run build && npm start` 打开白板，导航栏缺省展开、点行定位、
   收起后重载仍收起、缩略图可见 → verify: 逐项观察。
5. record 列全本轮 AC，本 plan 经 `open → resolved` 放行 → verify: resolved
   门通过（`rule-00001-BR-25`）。

## Out of Scope

- `spec-00008` §6 的全部条目。
- 服务端与 API 契约的任何改动。
