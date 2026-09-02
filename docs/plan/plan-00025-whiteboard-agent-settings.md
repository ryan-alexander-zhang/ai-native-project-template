---
id: plan-00025-whiteboard-agent-settings
type: plan
status: open
implements: [spec-00009-whiteboard-agent-settings]
---

# Plan: agent 设置——模型进条目、本地层、设置面板

> 落地 `spec-00009` 全部九条 FR 与 `decision-00017` 的各项裁决：服务端的条目
> 扩展、两层合并与设置 API（design-00001 §13），页面的设置面板（design-00002
> §18）；文档轮（decision-00017、spec-00009、四份文档的第二十六轮修订轮）已在
> `c66603bf` 与 `0bf54810` 接收。

## Design

Links only：

- [design-00001-docs-whiteboard](../design/design-00001-docs-whiteboard.md) —— §3
  流程配置契约（`model` / `env`、成对校验、`readAgentEntry`）；§5 受理快照与
  pty seam 的 `env`；§7 `GET /api/config` 的 agents 形态、`GET/PUT
  /api/settings/agents`；§10.1 `{model}` 与 headless seam 的 `env`；§13 本地层
  文件、合并、读取点、保存、进程环境与模型注入、接入验证纪律的适用面。
- [design-00002-whiteboard-ui](../design/design-00002-whiteboard-ui.md) —— §2 顶栏
  设置入口；§10 呈现状态族；§18 设置面板（入口与容器、列表、编辑表单、保存与
  就地更新、可访问性）。

## Tasks

T1 与 T2 并行（T1 只动 `tools/whiteboard/src/` 与 `test/`，T2 只动
`tools/whiteboard/web/`），T2 按 design-00001 §7 的契约编码；T3 在两者之后收口。

- **T1 — 服务端：条目扩展、两层合并、设置 API** (spec-00009-FR-1 … FR-6,
  FR-9；FR-3 / FR-8 的服务端)：`src/config.ts`（`model` / `env` 读取、`{model}`
  按形态成对校验、`readAgent` 抽为带 `at` 前缀的 `readAgentEntry`）；新增
  `src/agentSettings.ts`（本地层形态校验、`mergeAgents`、`EffectiveAgents` 的
  按次重读与告警去重、临时文件 + rename 的原子写）；`src/pty.ts` 与
  `src/headless.ts` 的两个 spawn seam 增 `env`，`fillModel` 与 `headlessArgs`
  的 `{model}` 替换；`src/sessionManager.ts` 与 `src/annotations.ts` 的
  `agents` 改为函数、`start` / `openAsk` 接受预解析条目、用户可见文案换词；
  `src/server.ts` 的 `GET /api/config` agents 形态、`GET/PUT
  /api/settings/agents`。测试落 `test/config.test.ts`、新增
  `test/agentSettings.test.ts`、`test/sessionManager.test.ts`、
  `test/headless.test.ts`、`test/annotations.test.ts`、`test/server.test.ts`。
- **T2 — 页面：设置面板与就地更新** (spec-00009-FR-7, FR-8；FR-3 的选择器侧)：
  按 decision-00001 的方式加入 shadcn `Input` / `Switch` / `Select`；新增
  `web/src/SettingsDialog.tsx`（列表、表单、遮罩与显示、保存与拒绝呈现）；
  `web/src/Board.tsx` 顶栏入口；`web/src/useBoard.ts` 的 `agents` /
  `askAgents` 推导（`headless` 布尔）、保存后重推导与已选名校正；
  `web/src/api.ts` 前端类型与两个新请求；`AskEntry.tsx` / `MaterialsInput`
  的已选名校正。测试落新增 `web/test/settings.test.tsx`，回归
  `agents.test.tsx`、`ask.test.tsx`。
- **T3 — 验收收口**：全量 `npm test`、`npm run typecheck`、
  `npm run test:coverage` 三门全绿、无门槛下调；`npm run build && npm start`
  手工走一遍保存→发起会话；每测带 `// spec-00009-AC-x.y` 溯源；写 record
  （`parent` 指向本 plan，`verifies: [spec-00009-whiteboard-agent-settings]`），
  并按 `spec-00001-AC-55.1` … `AC-55.4`、`spec-00005-AC-2.3` / `AC-8.1` /
  `AC-8.3` 的换名词补测；`tools/whiteboard/README.md`「Adding an agent CLI」
  增本地层一段。

## Detailed Acceptance Path

1. T1 落地 → verify: `spec-00009-AC-1.1` … `AC-6.6`、`AC-9.1` … `AC-9.4`、
   `AC-3.x` / `AC-8.4` 服务端半段的测试通过；`GET /api/config` 的 agents 项含
   `headless: boolean` 与 `source`。
2. T2 落地 → verify: `spec-00009-AC-7.1` … `AC-7.8`、`AC-8.1` … `AC-8.4` 的
   测试通过。
3. 三门全绿 → verify: 三个命令退出码 0，四个覆盖率数字不低于 90，无阈值改动。
4. 手工验证 → verify: 面板改 `model` 保存后新发起的会话参数含新模型；运行中
   会话不受影响；手改 `.whiteboard/agents.json` 为非法 JSON 后服务照常、面板
   呈现错误。
5. record 列全交付范围内每条 AC，本 plan 经 `open → resolved` 放行 → verify:
   resolved 门通过（`rule-00001-BR-25`）。

## Out of Scope

- `spec-00009` §6 的全部条目——尤其 Codex 接入与其 capture 内建。
- 模板自带 `whiteboard.config.yaml` 的 `claude` 条目加 `model`：须先实测
  `--model` 在两种形态下生效（design-00001 §13.5），本 plan 不做。
