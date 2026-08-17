---
id: plan-00009-whiteboard-ask-clarify
type: plan
status: resolved
implements: [spec-00001-docs-whiteboard, design-00001-docs-whiteboard, design-00002-whiteboard-ui]
---

# Plan: 澄清改会话、新增答疑——落地第八轮（FR-9 改写与 FR-45…48）

> 把 decision-00006 裁定的两个方向落进代码：澄清从「手动填写待澄清点」改为
> agent 会话逐题拷问（带焦点行、状态文件可恢复），新增答疑会话；旧澄清写路径
> 整体移除，record-00001 的 5 行 `n/a` 重验回填。

## Design

- [design-00001-docs-whiteboard](../design/design-00001-docs-whiteboard.md)
  §3（Workflow Engine：澄清/答疑的发起裁决、可澄清类型集与焦点行的承载分界）、
  §6（写路径：澄清与答疑不走写管道，写由会话内 agent 完成）。
- [design-00002-whiteboard-ui](../design/design-00002-whiteboard-ui.md) §3
  （控件映射：澄清改 `Button` 发起会话、新增答疑 `Button`、澄清对话框废弃）。
- [decision-00006-whiteboard-ask-clarify](../decision/decision-00006-whiteboard-ask-clarify.md)
  （全部取舍：方向切分、骨架进代码/焦点行进配置、JSON 状态文件、复用 CLI
  提问机制、单会话互斥）。

## Tasks

代码位于 `tools/whiteboard/`。W1 先行（W2、W3 都依赖它的裁决与配置面）；
W2 与 W3 可并行；W4 独立可随时做；W5 收尾。

| # | 任务 | 交付 | 覆盖 |
| --- | --- | --- | --- |
| W1 | 服务端发起与裁决 | 可澄清类型集内建（`rule-00001-BR-20`，与流转表同层）；`config.ts` 解析每类型焦点行并做启动校验（缺失、越界、为空/仅空白、非字符串、含换行 → 拒绝启动并指明类型）；澄清/答疑两个发起端点及其拒绝（澄清：非 `draft`、非可澄清类型；答疑：异常文档；二者：目标文档已删、会话占用中）；会话种类（advance/clarify/ask）进 `sessionManager`，commit 信息按种类落「澄清/答疑」（`gitLayer`）；接收成功时删除该文档的澄清状态文件；**移除旧澄清写路径**（`workflow.applyClarify`/`appendOpenQuestions` 与 review 路由的 `clarify` 分支；`hasOpenQuestions` 保留——BR-12 的接收门禁不变） | spec FR-9、FR-15、FR-18、FR-19、FR-48 及 `AC-9.1`…`9.4`、`AC-14.7`/`14.8`、`AC-16.3`/`16.4`、`AC-18.2`、`AC-19.2`、`AC-46.6`、`AC-48.2`…`48.6` |
| W2 | 任务指令构建与状态文件 | 澄清指令 = 目标与关系文档路径（FR-2 两段解析取路径、断链不入、只给路径不内联正文）+ 共享骨架（一次一题、至多 4 选项、推荐项首位标注「推荐」、自由输入、能自答不问）+ 该类型焦点行 + 状态文件契约（`.whiteboard/clarify/<id>.json`、逐题落盘、收尾删除）+ 收尾要求（未决点进 Open Questions、保持 `draft`、既定结论修订正文，小节定位约定入指令）；答疑指令 = 上下文路径 + 会话性质（多轮讨论、可修订 `docs/`、不动 status）；恢复：状态文件存在且合法 JSON 时注入内容并要求不重问，不存在或损坏时无恢复段 | spec FR-45、FR-46、FR-47（指令部分）及 `AC-45.1`…`45.5`、`AC-46.1`…`46.5`、`AC-47.3` |
| W3 | 前端工具栏 | 澄清从 `Dialog`+`Textarea` 改为 `Button` 直接发起会话（仅可澄清类型的节点呈现，位于评审组内与接收并列）；新增答疑 `Button`（ghost、任意状态、异常节点不呈现）；会话运行期间推进/澄清/答疑三个发起入口一并禁用；`api.ts` 对接新端点；澄清对话框相关组件与测试移除 | spec FR-3、FR-47（入口部分）及 `AC-3.1`（五入口）、`AC-2.4`（答疑入列排除）、`AC-9.3`、`AC-47.1`/`47.2`/`47.4`/`47.5` |
| W4 | 焦点行配置内容 | `whiteboard.config.yaml` 为五个可澄清类型各写一行焦点行（极简压缩：idea 问值不值得做与给谁做；prd 问角色、范围与价值取舍；spec 问 FR 边界与验收缺口；rule 问决策表未覆盖的分支与边界值；design 问结构取舍与失败模式），措辞域主可随时改——配置即入口 | spec `AC-48.1`/`48.3` 的夹具与真实配置 |
| W5 | 测试与收尾 | 32 条新 AC 全部落测（W1…W3 各表所列 + `AC-45.x` 全量）；rule 侧 `AC-11.x`/`20.x`/`21.x` 由 spec 侧测试携带验证并在 record 注明映射；旧澄清语义的既有测试移除或改写（`workflow`/`docService`/`server`/`toolbar` 四处）；record-00001 中 5 行 `n/a`（AC-3.1、AC-9.1…9.4）以新证据重验回填；新建 `record-00008` 承载本轮验收；实测见下方第 4 条 | 全部 |

## Detailed Acceptance Path

1. **每任务完成即验证**：`npm test` 全绿、`npm run typecheck` 无错、
   `npm run build` 通过；覆盖率 ≥90% 不回落；契约测试（真实 `docs/` 零诊断）
   保持常绿。
2. **新 AC**：spec 侧 32 条（`AC-9.1`…`9.4`、`AC-14.7`/`14.8`、`AC-16.3`/
   `16.4`、`AC-18.2`、`AC-19.2`、`AC-45.1`…`45.5`、`AC-46.1`…`46.6`、
   `AC-47.1`…`47.5`、`AC-48.1`…`48.6`）每条有对应通过的测试；修订过的
   `AC-3.1`（五入口）与 `AC-2.4`（答疑入列排除）的既有测试同步改写。指令
   内容类 AC（45.x/46.1…46.3/46.5/47.3/48.1）断言任务指令文本，agent 是否
   遵守骨架不在测试范围（spec 的口径：契约在指令，不在服从）。
3. **不回归**：预期变化只在旧澄清路径——`test/workflow.test.ts` 的
   applyClarify 用例、`test/docService.test.ts` 与 `test/server.test.ts` 的
   clarify review 用例、`web/test/toolbar.test.tsx` 的澄清对话框用例随旧语义
   移除或改写为新入口断言；其余全部用例（含 advance 会话生命周期、接收门禁
   `AC-8.4`）应当一字不改地继续通过——`hasOpenQuestions` 与接收门禁若需要
   改，说明动错了地方。
4. **实测核对**：用本仓真实文档——(a) 对一份 `draft` 的可澄清文档发起澄清，
   在终端里答两题后杀掉白板服务进程，重启后再次发起澄清，确认指令带着已答
   两题恢复、不重问；(b) 答完全部问题让会话收尾，确认 Open Questions 落盘、
   状态文件删除、commit 信息为「澄清」且不含 `.whiteboard/`；(c) 对一份
   `active` 的 record 发起答疑并让 agent 按结论改一处正文，确认 status 不变、
   commit 信息为「答疑」；(d) 澄清会话运行中尝试发起答疑与推进，确认均被拒
   且入口禁用；(e) 执行接收后确认遗留状态文件被删。任一不成立，据实记入
   `record-00008`，不得默认通过。收尾后把 `docs/` 与 `.whiteboard/` 恢复
   原状、重跑契约测试并核对 `git status` 回到基线。
5. **收尾门槛**：未参与实现的 subagent 按文档核验每条 GWT 有通过的测试、
   范围内无 unverified 条目；record-00001 的 5 行 `n/a` 已重验回填；
   `record-00008` 建好并链上 GWT id 后本 plan 方可 `resolved`。任何 gap
   阻塞 `resolved`。
