---
id: plan-00003-whiteboard-relation-edges
type: plan
status: resolved
implements: [spec-00001-FR-1, design-00001-docs-whiteboard, design-00002-whiteboard-ui]
---

# Plan: 画出关系边，并把布局改成类型分列

> 让 front matter 的每条关系在画布上真的成为一条边（`issue-00002`），
> 并把布局从「关系边推层次」换成「列＝类型、行＝id 序」（`issue-00003`）。

## Design

- [design-00002-whiteboard-ui](../design/design-00002-whiteboard-ui.md) §2 ——
  画布内部的类型分列网格；§4 —— 节点的四向 handle、锚点选择与箭头方向。
- [design-00001-docs-whiteboard](../design/design-00001-docs-whiteboard.md) §1、§2
  —— 布局选型与模块结构。
- [decision-00002-whiteboard-layout](../decision/decision-00002-whiteboard-layout.md)
  —— 去掉 ELK 的理由与代价。
- 两个被修的缺陷：[issue-00002](../issue/issue-00002-relation-edges-never-render.md)、
  [issue-00003](../issue/issue-00003-stage-flow-reads-backwards.md)。

## Tasks

代码位于 `tools/whiteboard/`。L1 与 L2 可并行；L3 依赖 L1（要用列序）与 L2
（要用 handle id）；L5 依赖 L1–L3。

| # | 任务 | 交付 | 覆盖 |
| --- | --- | --- | --- |
| L1 | 布局改造 | `web/src/layout.ts` 改为同步纯函数 `layoutGraph(graph, typeOrder)`：列序取 `GET /api/config` 的 `types` 键顺序，空类型不占列，未知类型按字典序排其后、`type` 缺失者最后；同列按 `(id, path)` 升序；`useBoard` 改为**图与配置都到位后**才落位（现为两个独立请求，见 design-00002 §2 末段）；从 `package.json` 与 `package-lock.json` 移除 `elkjs` | decision-00002 §2；spec AC-1.6…AC-1.9、AC-1.12、AC-1.13 |
| L2 | 节点锚点 | `web/src/NodeCard.tsx` 补四方位各一对 source/target 共 8 个锚点，以 `opacity: 0` 隐藏（**不可用 `display: none`**，否则量不到位置＝重新制造 issue-00002）；`Board.tsx` 设 `nodesConnectable={false}` 关掉新出现的手工连线交互 | issue-00002 §6；design-00002 §4；spec AC-1.14 |
| L3 | 边锚点与箭头 | `web/src/canvasModel.ts` 的 `toFlowEdges(graph, placed)` 依两端位置选 `sourceHandle`/`targetHandle`（跨列走左右、同列走上下、自环走上下），并加 `markerEnd` 箭头指向被引用文档 | design-00002 §4；spec AC-1.10、AC-1.11 |
| L4 | 配置列序 | `whiteboard.config.yaml` 的 `types` 按 decision-00002 §2 的列序表重排，并就地注明「声明顺序即白板列序」 | decision-00002 §2、§4 |
| L5 | 测试 | `web/test/setup.ts` 补两个桩（会上报尺寸且带 `borderBoxSize` 的 `ResizeObserver`、`DOMMatrixReadOnly`）；补 DOM 级边断言与 `onError` 收到 `008` 的断言；AC-1.6…AC-1.14 全部落测；**补一条钉住配置列序的测试**（decision-00002 §4 承诺的第二条缓解）；按 design-00002 §7 第 6–8 项改写受影响的既有断言与两处签名调用点 | issue-00002 §5；issue-00003 §5；decision-00002 §4 |
| L6 | 文档收尾 | 回填 decision-00002 §4 的实测产物体积与 design-00002 §4 要求的实际箭头方向比例；新建 `record-00002` 承载本 plan 的验收（`record-00001` 的 `parent` 已指向 plan-00001，单值字段不可复用），并更新 `record-00001` 中被本次推翻的 AC-1.1/AC-1.2/AC-2.2 证据行；两个 issue 先促为 `open` 再置 `resolved`（`draft → resolved` 被 `rule-00001-BR-2…BR-9` 拒绝） | — |

## Detailed Acceptance Path

1. **每任务完成即验证**：`npm test` 全绿、`npm run typecheck` 无错、
   `npm run build` 通过。
2. **复现先失败**：`issue-00002` §5 与 `issue-00003` §5 的三条测试在改动前确认
   失败，改动后通过。
3. **新 AC**：`spec-00001-AC-1.6` … `AC-1.14` 共 9 条，每条有对应通过的测试。
4. **不回归**：`AC-1.1`…`AC-1.5`、`AC-2.1`…`AC-2.4` 以及 FR-3…FR-27 的既有 AC
   仍全部通过。design-00002 §7 第 6–8 项列出的三处必然失败**不是回归**，其余
   查询不到或断言不成立的，按真实回归处理。
5. **覆盖率**：自有代码仍 ≥90% 行/分支/函数。`layout.ts` 是纯函数，其分支
   （已声明类型、空类型、未知类型、`type` 缺失、id 相同）须全部有用例。
6. **实测核对**：用本仓真实文档启动一次白板，人工确认 `idea` 最左、`record`
   最右、`design-00001` 在 `design-00002` 之上，且每条 front matter 声明都有
   一条可见的边、箭头落在被引用的一端。
7. **收尾门槛**：由未参与实现的 subagent 按文档核验 L1–L6 与本节各条；
   `record-00002` 建好并链上 GWT id 后方可将本 plan 置 `resolved`。任何 gap
   阻塞 `resolved`。
