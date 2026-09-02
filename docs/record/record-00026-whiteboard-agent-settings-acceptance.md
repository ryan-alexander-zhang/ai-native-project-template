---
id: record-00026-whiteboard-agent-settings-acceptance
type: record
status: active
parent: plan-00025-whiteboard-agent-settings
verifies: [spec-00009-whiteboard-agent-settings]
---

# 验收记录：agent 设置——模型进条目、本地层、设置面板

对 [plan-00025-whiteboard-agent-settings](../plan/plan-00025-whiteboard-agent-settings.md)
的验收。交付范围为 `spec-00009` 全部 9 条 FR 的 55 条 AC；范围外一并验收的是
`spec-00001-AC-55.1` … `AC-55.4` 与 `spec-00005-AC-2.3` / `AC-8.1` / `AC-8.2` /
`AC-8.3` 八条——前七条随本轮把「流程配置的 agents」换名词为「有效 agent 列表」，
口径不变，列出其现有测试；`spec-00005-AC-8.3` 是本轮新增的 AC，其测试由本 plan
的 T1 新写。`spec-00009` §6（Codex 接入）与模板
`whiteboard.config.yaml` 给 `claude` 加 `model` 两项在本 plan 的 Out of Scope 内，
不在此验收。T1（服务端）、T2（页面）、T3（收口）三段落地，第二十六轮据实校正
（`33f5ac9e`）的两处在 T3 补齐，见「实现期的既定取舍」。测试路径相对
`tools/whiteboard/`。

## 质量门

三门在 Part A 的两处补齐**之后**整体重跑，命令均在 `tools/whiteboard/` 下执行：

- `npm test`：退出码 0，58 个文件、1801 个测试全部通过（T2 结束时的 58 / 1796
  基线 + 本轮 5 条）。
- `npm run typecheck`：退出码 0，`tsc --noEmit` 无输出。
- `npm run test:coverage`：退出码 0，statements 98.68% / branches 95.33% /
  functions 98.57% / lines 99.26%（4426/4485、2596/2723、1384/1404、3804/3832），
  `vitest.config.ts` 只对 lines / branches / functions 设 90 门槛，三项皆过，
  statements 一并列出仅作参考；阈值与排除项一字未改。
- `npm run build`：退出码 0（附带的 chunk 大小提示是既有的，非错误）。

## 验收清单

| 被验 id | 测试 | 结果 |
| --- | --- | --- |
| spec-00009-AC-1.1 | fills the model into the interactive args and into a headless call alike (test/sessionManager.test.ts) | pass |
| spec-00009-AC-1.2 | lays the entry’s env over the board’s own for both seams (test/sessionManager.test.ts) | pass |
| spec-00009-AC-1.3 | starts an entry that declares neither key exactly as it did before (test/sessionManager.test.ts) | pass |
| spec-00009-AC-1.4 | starts on an empty env, whose child gets the board’s environment unchanged (test/sessionManager.test.ts) | pass |
| spec-00009-AC-2.1 | rejects a {model} placeholder in args when the entry names no model (test/config.test.ts) | pass |
| spec-00009-AC-2.2 | rejects a model the args hold no placeholder for (test/config.test.ts) | pass |
| spec-00009-AC-2.3 | rejects a model the declared resume form holds no placeholder for, naming that form (test/config.test.ts) | pass |
| spec-00009-AC-2.4 | rejects an env that is a list rather than a mapping (test/config.test.ts) | pass |
| spec-00009-AC-2.5 | rejects an empty model (test/config.test.ts) | pass |
| spec-00009-AC-2.6 | starts on a model paired with a placeholder in args and no headless declaration at all (test/config.test.ts) | pass |
| spec-00009-AC-3.1 | runs a session on the model the local layer overrides the project one with (test/server.test.ts) | pass |
| spec-00009-AC-3.2 | downloads a locally added entry after the project ones, in that order (test/server.test.ts) | pass |
| spec-00009-AC-3.3 | runs an unnamed session on the entry the local layer makes the default (test/server.test.ts) | pass |
| spec-00009-AC-3.4 | sends an unnamed ask to the first entry still in the list once one is disabled (test/server.test.ts) | pass |
| spec-00009-AC-3.5 | downloads the project layer entry for entry when there is no local file (test/server.test.ts) | pass |
| spec-00009-AC-3.6 | refuses a session that names a disabled entry, and starts nothing (test/server.test.ts) | pass |
| spec-00009-AC-3.7 | picks up a local file written by hand while it is running, with no restart (test/server.test.ts) | pass |
| spec-00009-AC-3.8 | leaves a disabled entry out of the config download altogether (test/server.test.ts) | pass |
| spec-00009-AC-4.1 | starts on an unparsable local file, falls back to the project layer and says why (test/server.test.ts) · names a local file that will not parse (web/test/settings.test.tsx) | pass |
| spec-00009-AC-4.2 | ignores the whole layer when it overrides cwd, model and all, naming that key (test/server.test.ts) · names the entry whose cwd the local layer tried to override (web/test/settings.test.tsx) | pass |
| spec-00009-AC-4.3 | ignores a layer that disables every entry there is, saying the list would be empty (test/server.test.ts) · says so when the merge leaves no agent at all (web/test/settings.test.tsx) | pass |
| spec-00009-AC-4.4 | falls back to the project layer at the next start when the file is edited into nonsense (test/server.test.ts) | pass |
| spec-00009-AC-4.5 | ignores an override of an entry the project layer no longer has, and keeps the rest (test/server.test.ts) · names an override that points at no project entry (web/test/settings.test.tsx) | pass |
| spec-00009-AC-4.6 | ignores a layer whose default is also disabled, naming that entry (test/server.test.ts) · names a default that points at a disabled entry (web/test/settings.test.tsx) | pass |
| spec-00009-AC-4.7 | ignores a layer whose added entry declares a cwd of its own, naming that key (test/server.test.ts) · names an added entry that declared a cwd of its own (web/test/settings.test.tsx) | pass |
| spec-00009-AC-4.8 | ignores a disabled name nothing answers to, and keeps the rest of the layer (test/server.test.ts) · names a disable that points at no entry (web/test/settings.test.tsx) | pass |
| spec-00009-AC-4.9 | ignores a layer that adds an entry by a project entry’s own name, saying so (test/server.test.ts) · names an entry the local layer appended over a project one (web/test/settings.test.tsx) | pass |
| spec-00009-AC-5.1 | writes the saved local layer to .whiteboard/agents.json (test/server.test.ts) | pass |
| spec-00009-AC-5.2 | runs the next session on the saved list, with no restart (test/server.test.ts) | pass |
| spec-00009-AC-5.3 | leaves a running session on the argv it started with when the model is saved over (test/server.test.ts) | pass |
| spec-00009-AC-5.4 | lets a call already in flight finish and file its answer, whatever is saved meanwhile (test/server.test.ts) | pass |
| spec-00009-AC-5.5 | opens every question of a batch on the entry it resolved at admission (test/annotations.test.ts) | pass |
| spec-00009-AC-5.6 | overwrites an unparsable local file with the content the panel saved (test/server.test.ts) · saves over a local file that would not parse (web/test/settings.test.tsx) | pass |
| spec-00009-AC-6.1 | refuses a save whose model no args hold a placeholder for, and writes nothing (test/server.test.ts) · shows a refused save under the field it names (web/test/settings.test.tsx) | pass |
| spec-00009-AC-6.2 | refuses the very same save the very same way the second time (test/server.test.ts) · refuses the same content the same way a second time (web/test/settings.test.tsx) | pass |
| spec-00009-AC-6.3 | refuses an added entry with no command, naming that key (test/server.test.ts) · names the command of an added entry that has none (web/test/settings.test.tsx) | pass |
| spec-00009-AC-6.4 | reports a save it could not write, leaving no file and the list as it was (test/server.test.ts) | pass |
| spec-00009-AC-6.5 | reports the same write failure the second time, the list still as it was (test/server.test.ts) · reports a failed write each time, keeping the form (web/test/settings.test.tsx) | pass |
| spec-00009-AC-6.6 | refuses a headless declaration missing the question placeholder, naming that form (test/server.test.ts) | pass |
| spec-00009-AC-7.1 | lists both layers, each entry with where it came from (web/test/settings.test.tsx) | pass |
| spec-00009-AC-7.2 | puts a field back to the project value when its override is undone (web/test/settings.test.tsx) | pass |
| spec-00009-AC-7.3 | leaves the working directory read-only and the other keys editable (web/test/settings.test.tsx) | pass |
| spec-00009-AC-7.4 | still lists a disabled entry, and lets it be enabled again (web/test/settings.test.tsx) | pass |
| spec-00009-AC-7.5 | lists the one project entry and still offers a local one to be added (web/test/settings.test.tsx) | pass |
| spec-00009-AC-7.6 | shows an added entry as running in docs, uneditably (web/test/settings.test.tsx) | pass |
| spec-00009-AC-7.7 | masks every env value (web/test/settings.test.tsx) | pass |
| spec-00009-AC-7.8 | shows one env value in the clear when it is asked for (web/test/settings.test.tsx) · masks an env value again when it is hidden (web/test/settings.test.tsx) | pass |
| spec-00009-AC-8.1 | draws the agent picker once a saved local entry makes the list two long (web/test/settings.test.tsx) | pass |
| spec-00009-AC-8.2 | takes the picker away once a saved deletion makes the list one long (web/test/settings.test.tsx) | pass |
| spec-00009-AC-8.3 | widens the ask choice once a saved entry declares a headless form (web/test/settings.test.tsx) | pass |
| spec-00009-AC-8.4 | refuses an ask outright once the last headless entry is disabled (test/server.test.ts) · draws neither ask entry once the one headless agent is disabled and saved (web/test/settings.test.tsx) | pass |
| spec-00009-AC-9.1 | refuses a follow-up whose agent the local layer no longer declares, leaving the thread whole (test/server.test.ts) | pass |
| spec-00009-AC-9.2 | resumes the thread once the same entry is added back with its headless form (test/server.test.ts) | pass |
| spec-00009-AC-9.3 | refuses a follow-up whose agent the local layer stripped the headless form from (test/server.test.ts) · takes a project entry’s headless declaration away for a null override (test/agentSettings.test.ts) · writes a null headless override when a project entry is told to declare none (web/test/settings.test.tsx) | pass |
| spec-00009-AC-9.4 | opens a new thread on another document normally while an old one’s agent is gone (test/server.test.ts) | pass |
| spec-00009-FR-7 | drops the headless key altogether when an added entry is told to declare none (web/test/settings.test.tsx) | pass |
| spec-00009-FR-8 | falls back to the first agent once the picked one leaves the list (web/test/annotationList.test.tsx) | pass |
| spec-00001-AC-55.1 | starts an audit session on the agent the request names (test/server.test.ts) · sends the agent the user picked (web/test/agents.test.tsx) | pass |
| spec-00001-AC-55.2 | starts an advance on the first configured agent when none is named (test/server.test.ts) · names the first agent on a session it was not asked about (web/test/agents.test.tsx) | pass |
| spec-00001-AC-55.3 | answers 422 and starts nothing for an agent the config does not declare (test/server.test.ts) | pass |
| spec-00001-AC-55.4 | is not drawn when the config declares one agent (web/test/agents.test.tsx) · names no agent at all when there is only one (web/test/agents.test.tsx) | pass |
| spec-00005-AC-2.3 | offers only the agents that declare a headless form, and keeps a thread on its own (test/server.test.ts) · narrows the choice to the agents that declare a headless form (web/test/agents.test.tsx) | pass |
| spec-00005-AC-8.1 | rejects a headless declaration missing a required placeholder (test/config.test.ts) | pass |
| spec-00005-AC-8.2 | runs the declared first form and then the declared resume form (test/server.test.ts) | pass |
| spec-00005-AC-8.3 | starts on a local entry whose headless declaration is ill-formed, naming that declaration (test/server.test.ts) | pass |

无未覆盖或未通过条目。`spec-00009-FR-7` 与 `spec-00009-FR-8` 各多一行：第二十六轮
据实校正补的两处（追加条目的「无 headless」写法、标注列表统一提交的已选名校正）
在 `spec-00009` 里没有独立 AC，按 FR 落行。

## 手工验证（plan §Detailed Acceptance Path 第 4 项）

`npm run build` 后 `PORT=4199 npm start`，白板向上找到仓库根的
`whiteboard.config.yaml`（项目层只声明 `claude` 一条，带 headless）。逐步 curl，
观察结果照录。**未对真实 claude CLI 发起任何会话**——spawn 的 argv 由
`test/sessionManager.test.ts` 与 `test/headless.test.ts` 证明，跑一次真会话只是花钱。

| 步 | 请求 | 观察到的结果 |
| --- | --- | --- |
| 1 | `GET /api/config` | 200；`agents = [{"name":"claude","headless":true,"source":"project","default":false}]`——`headless` 是布尔、`source` 在（design-00001 §7）；`agentSettings = {"notices":[]}` |
| 2 | `GET /api/settings/agents` | 200；`project` 名字为 `["claude"]`，`local` 为 `null`，`captures` 为 `["claude-json"]`，`effective` 同第 1 步，无 `error`、`notices` 为空 |
| 3 | `PUT` `{"overrides":{"claude":{"model":"sonnet","args":["--model","{model}"]}}}` | **422**：`{"error":"config: \`overrides.claude.model\` is set, so \`overrides.claude.headless.first\` must hold a \`{model}\` placeholder","at":"overrides.claude.headless.first"}`；未写盘（`.whiteboard/agents.json` 不存在），`GET /api/config` 的 `source` 仍为 `project`。这是 `spec-00009-FR-2` 的成对校验落在**合并结果**上：项目层的 `claude` 声明了 headless，只覆盖 `args` 会让模型在答疑形态里静默丢掉 |
| 3′ | 同一覆盖，另把 `headless.first` / `resume` 覆盖为含 `--model {model}` 的形态 | 200：`{"effective":[{"name":"claude","headless":true,"source":"overridden","default":false}],"notices":[]}`；`GET /api/config` 的 `source` 为 `overridden`；`.whiteboard/agents.json` 存在，655 B |
| 4 | 手写 `{ not json` 进 `.whiteboard/agents.json`，再 `GET /api/config` | 200；`agents` 退为项目层（`source":"project"`）；`agentSettings.error.message` 为 `agent settings: .whiteboard/agents.json is not readable JSON — Expected property name or '}' in JSON at position 2 (line 1 column 3)` |
| 5 | `PUT` `{"disabled":["claude"]}` | **422**：`{"error":"agent settings: the effective agent list would be empty"}` |
| 5′ | `PUT` `{"overrides":{"claude":{"headless":null}}}` | 200：`{"effective":[{"name":"claude","headless":false,"source":"overridden","default":false}],"notices":[]}`；文件内容恰为该覆盖；`GET /api/config` 的该条 `headless` 为 `false`——`spec-00009-AC-9.3` 的前提由面板可达 |
| 5″ | `PUT` `{"overrides":{"claude":{"model":null}}}` | **422**：`{"error":"agent settings: \`overrides.claude.model\` may not be null","at":"overrides.claude.model"}`——除 `headless` 外不收 `null` |
| 6 | 删掉 `.whiteboard/agents.json`，`GET /api/config` | 200；`agents` 回到 `[{"name":"claude","headless":true,"source":"project","default":false}]`，无 `error`；服务停止，`.whiteboard/` 只剩 `annotations/`、`asks/`、`clarify/`、`sessions/`，工作区无残留 |

## 实现期的既定取舍

- **`headless: null` 的服务端只需补「拒其它键的 null」**：`readHeadless` 早已把
  `null` 读作「无声明」，所以 `overrides.<name>.headless: null` 在 T1 就已端到端
  成立（`test/server.test.ts` 的 AC-9.3 测试本来就走 `PUT`，故未另补一条）。
  design-00001 §13.1 补的「其他键不接受 `null`」才是缺口：`agentSettings.ts` 增
  `rejectNulls`，`applyOverrides` 放行 `headless` 一个键、`addEntries` 一个都不
  放——追加条目没有可撤的项目声明。
- **「无 headless」开关落成一个模型函数而非一个字段写入**：`withField` 的
  `FieldValue` 是 `NonNullable`，硬塞 `null` 会把「撤声明」和「写声明」搅成一个
  类型。改为 `settingsModel.withoutHeadless(local, card)`，项目条目写
  `headless: null`、追加条目删键——两层的语义差别就写在一处。前端类型随之把
  `AgentOverride.headless` 放宽为 `HeadlessDecl | null`，`LocalAgentEntry` 保持
  不含 `null`，与服务端的两条规则一一对应。
- **`agentCards` 的合并要认 `null`**：`{...entry, ...override}` 会把 `null` 带进
  卡片，headless 徽标与答疑可选集就都读错。抽 `merged(entry, override)` 一处按
  服务端的读法归一。
- **两处既有测试改了 switch 的查询**：表单多一个 `Switch` 之后
  `getByRole('switch')` 在一张卡片里会命中两个，AC-7.4 与 AC-8.4 的查询加上
  `{ name: 'Disabled' }`。断言与场景一字未动。
- **`web/test/annotationList.test.tsx` 的 `list()` 多返回一个 `relist`**：已选名
  校正只有在「列表在对话框开着时变了」才看得见，需要重绘同一棵树。既有用例的
  返回形状用展开保持不变。
- **手工验证的第 3 步按实际改成两步**：plan 里写的那条 `PUT` 会被成对校验拒掉
  （项目层的 `claude` 声明了 headless）。照录 422，再补一条把三个数组都带上
  `{model}` 的覆盖，走完「保存 → `source` 变 `overridden` → 文件落盘」。这不是
  缺陷：`spec-00009-AC-2.3` 要的就是这个拒绝，只是 plan 的示例请求没算上它。
