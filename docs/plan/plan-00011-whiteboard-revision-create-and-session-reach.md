---
id: plan-00011-whiteboard-revision-create-and-session-reach
type: plan
status: resolved
implements: [spec-00001-FR-53, spec-00001-FR-54, spec-00001-FR-55, spec-00001-FR-56, rule-00001-BR-3, rule-00001-BR-26, rule-00001-BR-27, decision-00008-whiteboard-revision-create-and-session-reach, design-00001-docs-whiteboard, design-00002-whiteboard-ui]
---

# Plan: 修订轮、新建入口与会话历史——落地第十一轮（FR-53…56、BR-3/BR-26）

对 [decision-00008](../decision/decision-00008-whiteboard-revision-create-and-session-reach.md)
八项裁定的实现。含两项无新 FR 的工程裁定：白板 commit 带 `--no-verify`
（design-00001 §7）、图解析缓存（spec §7 非功能项）；以及一个先立后修的缺陷
`issue-00014`（lastFinding 陈旧）。

## Design

- [design-00001-docs-whiteboard](../design/design-00001-docs-whiteboard.md)
  §2（转写落盘、agent 可选、图缓存与产物重验）、§7（`POST /api/docs`、
  `GET /api/docs/new`、`GET /api/sessions/history*`、会话 `agent` 参数、
  config 类型集、`--no-verify`、`wb(create)`）。
- [design-00002-whiteboard-ui](../design/design-00002-whiteboard-ui.md)
  §3（新建按钮与对话框、会话历史入口、agent 选择）。

## Tasks

T1…T4 相互独立可并行；T5（前端）依赖 T1…T3 的 API 形状（契约已在 design
固定，可并行开发、联调收尾）；T6 收口。

- **T1 — 修订轮**（BR-3）：`statusRules.ts` 的 living `active` 增目标
  `draft`，`statusRules.test.ts` 中断言旧值的 AC-3.1 期望随之更新；既有
  draft 机制零改动即适用（AC-3.1…3.5、AC-6.5）。
- **T2 — 新建**（FR-53、BR-26）：config 增 `entry` 校验（FR-15 扩展，
  AC-53.5/53.6）；`GET /api/create`（取号 + 模板预填）与 `POST /api/docs`
  （建档 + `wb(create)` commit，409/422 分支）；slug 校验。
- **T3 — 会话历史与 agent 可选**（FR-54、FR-55）：会话收尾落盘
  `.whiteboard/sessions/<会话 id>.json`（元数据 + 转写，失败不阻塞收尾）；
  `GET /api/sessions/history*`；四个会话端点接受可选 `{agent}`（缺省第一条、
  未知 422）。
- **T4 — 工程债**：commit 带 `--no-verify`；图解析按变更失效缓存（watcher
  与写路径失效）；`issue-00014`——先按 `docs/issue/README.md` 立 issue 并以
  失败测试复现「产物修复后仍标异常」，再改为图构建时按磁盘现状重验、通过即
  清除。
- **T5 — 前端**：顶栏新建按钮 + 对话框（`FilePlus`，entry 为空不呈现）、
  会话历史列表与转写查看（`History`）、agent 选择（`Bot`，单 agent 不呈现）；
  澄清/审计入口的类型集改取 `GET /api/config`（FR-56，删除本地副本）。
- **T6 — 测试与验收**：覆盖交付范围内全部 AC；质量门（typecheck、覆盖率
  ≥90%）不降；写 record（`parent` 指向本 plan），以本 plan 过 resolved 门
  收口。

## Detailed Acceptance Path

1. `npm test`、`npm run typecheck`、覆盖率门全绿 → verify: 命令退出码与阈值。
2. 交付范围内每条 AC 在 record 验收清单有通过行 → verify: 检视面板覆盖三态。
3. 本 plan 经白板 `open → resolved` 放行 → verify: 门通过与 `wb(status)`
   commit。

## Out of Scope

- 多会话并行；会话历史清理/检索；修订轮 diff 呈现；新建非入口类型
  （spec §6）。
