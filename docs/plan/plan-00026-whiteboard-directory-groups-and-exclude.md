---
id: plan-00026-whiteboard-directory-groups-and-exclude
type: plan
status: resolved
implements: [spec-00010-whiteboard-directory-groups-and-exclude]
---

# Plan: 配置排除与目录组

> 落地 `spec-00010` 全部十二条 FR 与 `decision-00018` 的各项裁决：服务端两处
> 小改（流程配置多读一个键、扫描多过一遍过滤），页面侧一次纯变换加两个新
> 组件；含 design-00001 §14 与 design-00002 §19 的第二十七轮修订轮。

## Design

Links only：

- [design-00001-docs-whiteboard](../design/design-00001-docs-whiteboard.md) ——
  §14 配置排除：`exclude` 字段契约（14.1）、匹配与扫描（14.2）、下游后果
  （14.3）、对 BR-18 与 docs/README 的追注（14.4）、测试与配置（14.5）；
  §3 流程配置契约的 `exclude` 行；§11.3 第 2 款的加注。
- [design-00002-whiteboard-ui](../design/design-00002-whiteboard-ui.md) —— §19
  目录组：归组模型（19.1）、折叠变换与画布（19.2）、展开态（19.3）、导航栏
  （19.4）、缩略图（19.5）、可访问性与测试（19.6）；§1 令牌表的 `--group-node`
  行；§4 组节点条；§8 验收归属表的第二十七轮行；§10、§17 的加注。

## Tasks

T1 是文档轮，先于一切代码。T2 与 T4 互相独立、可并行；T3 依赖 T2（要读到
`FlowConfig.exclude`）；T5 依赖 T4；T6 依赖 T4 与 T5；T7 收口。**design-00001
与 design-00002 重新接收之前，不得开写 T2 … T6 的代码。**

- **T1 — 两份 design 的第二十七轮收口**：本轮已落笔（design-00001 §14、
  design-00002 §19、各处加注、两份 `informs`）；审计余项修正 → 接收；本 plan
  随之 `draft → open`。
- **T2 — 流程配置读 `exclude`** (spec-00010-FR-2；FR-1 的缺失/null/空分支)：
  `src/config.ts` 增 `readExclude`，`FlowConfig` 增 `exclude: string[]`，按
  design-00001 §14.1 的逐项校验与错误位置 `exclude[<i>]`；`/api/config` 路由与
  `ConfigPayload` 剔除 `exclude`（§14.1）；`package.json` 的 `engines` 已声明，
  `@types/node` 对齐到 24 系（§14.2）。
- **T3 — 扫描过滤** (spec-00010-FR-1, FR-3, FR-11, FR-12)：`src/docRepository.ts`
  的 `listDocFiles` 接 `exclude`，以 `path.posix.matchesGlob` 过滤，`readGraph`
  传入，按 design-00001 §14.2；FR-3/FR-11/FR-12 与共写收口（`AC-1.13`）零代码，
  只落测试钉住 §14.3 表中的每一行。
- **T4 — 归组模型与折叠变换** (spec-00010-FR-4, FR-5 的边)：`web/src/layout.ts`
  的 `groupKey`、`Column`/`DirectoryGroup`、`orderedColumns` 改型、
  `layoutGraph` 接 `expanded`；`web/src/canvasModel.ts` 增 `foldGraph` 与
  `representative`，`toFlowEdges`/`suppressedNodes` 改喂折叠后的图，按
  design-00002 §19.1/§19.2。
- **T5 — 组节点与展开态** (spec-00010-FR-5, FR-6, FR-7)：新增
  `web/src/GroupNodeCard.tsx` 与 `web/src/directoryGroups.ts`（本地持久化两
  函数）；`Board.tsx` 持有 `expandedGroups`/`toggleGroup`、选中变化即展开的
  效果、`toFlowNodes` 出组节点；`index.css` 增 `--group-node` 令牌，按
  design-00002 §19.2/§19.3/§19.5。
- **T6 — 导航栏镜像与缩略图** (spec-00010-FR-8, FR-9, FR-10)：
  `web/src/sidebarModel.ts` 的 `TypeGroup` 增 `top`/`directories`，
  `Sidebar.tsx` 渲染目录组头与缩进行、接 `expandedGroups`/`onToggleGroup`；
  `Board.tsx` 的 `minimapClass` 增组节点分支，`index.css` 增 `minimap-group`，
  按 design-00002 §19.4/§19.5。FR-9 是既有刷新通路在新模型上的行为，只落测试。
- **T7 — 测试与验收收口**：`test/config.test.ts`（`AC-1.6`/`AC-1.7`、
  `AC-2.1`…`AC-2.9`）、`test/docRepository.test.ts` 或 `docService.test.ts`
  （`AC-1.1`…`AC-1.5`、`AC-1.8`…`AC-1.12`、`AC-3.x`、`AC-11.x`）、workflow 取号
  用例（`AC-12.x`）、`test/cowrite.test.ts`（`AC-1.13`）；`web/test/layout.test.ts`
  （`AC-4.x`）、新增 `web/test/foldGraph.test.ts`、`web/test/canvas.test.tsx`
  （`AC-5.x`、`AC-6.x`、`AC-7.x`、`AC-10.x`）、`web/test/sidebar.test.tsx`
  （`AC-8.x`）、`web/test/refresh.test.tsx`（`AC-9.x`）；每测带 `// <AC id>`
  溯源标注；既有测试回归（`orderedColumns` 返回型改变、`toFlowNodes` 计数）；
  `npm test`、`npm run typecheck`、覆盖率门不降；写 record（`parent` 指向本
  plan，`verifies: [spec-00010-whiteboard-directory-groups-and-exclude]`）；
  `tools/whiteboard/README.md` 增 `exclude` 与目录组各一行。

## Detailed Acceptance Path

1. `decision-00018`、`spec-00010` 已 `active`；design-00001 与 design-00002
   经审计重新接收 → verify: 四份文档均 `active`，design-00001 含 §14、
   design-00002 含 §19；`CONTEXT.md` 含七个新词条且七个既有词条已修订；
   `whiteboard.config.yaml` 含 `exclude: []`。
2. T2 … T6 落地 → verify: `spec-00010-AC-1.1` … `AC-12.2` 对应测试全部通过。
3. 全量测试、typecheck、覆盖率门全绿 → verify: 三个命令退出码为 0，四个覆盖率
   数字不低于阈值，无门槛下调、无被压制的发现。
4. 手工验证：在本仓 `docs/reference/` 下临时建 `stripe/` 子目录放两份合式
   reference 与一个 `source/` 语料文件，`exclude: ['reference/*/source/**']`，
   `npm run build && npm start` → verify: reference 列出现折叠的 `stripe`
   组节点计数 2、语料文件不在板上也不在异常清单；点组节点展开、导航栏同步；
   命令面板选中组内文档时组自动展开；重载后展开态保持。验证后撤掉临时文件。
5. record 列全本轮 AC，本 plan 经 `open → resolved` 放行 → verify: resolved
   门通过（`rule-00001-BR-25`）。

## Out of Scope

- `spec-00010` §6 的全部条目（多级嵌套、README 作组名、阈值折叠、取反与
  gitignore 式目录形态、热重载、语义缩放、设置面板编辑 `exclude`、手动排序、
  组节点工具栏、虚拟化、展开后的视口调整）。
- `GET /api/config` 与 `/api/graph` 契约的任何改动（design-00001 §14.1：
  `exclude` 不下发）。
- 对 `rule-00001` 与 `docs/README.md` 的追注已随 spec-00010 接收完成，本 plan
  不再触碰。
