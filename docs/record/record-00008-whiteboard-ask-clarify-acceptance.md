---
id: record-00008-whiteboard-ask-clarify-acceptance
type: record
status: active
parent: plan-00009-whiteboard-ask-clarify
verifies: [spec-00001-FR-9, spec-00001-FR-45, spec-00001-FR-46, spec-00001-FR-47, spec-00001-FR-48, rule-00001-BR-11, rule-00001-BR-20, rule-00001-BR-21, spec-00001-AC-2.4, spec-00001-AC-3.1, spec-00001-AC-14.7, spec-00001-AC-14.8, spec-00001-AC-16.3, spec-00001-AC-16.4, spec-00001-AC-18.2, spec-00001-AC-19.2]
---

# 验收记录：澄清改会话、新增答疑

对 [plan-00009-whiteboard-ask-clarify](../plan/plan-00009-whiteboard-ask-clarify.md)
的验收——第八轮（decision-00006）：澄清从手动填写改为 agent 会话逐题拷问、
新增答疑会话、焦点行进流程配置、澄清进度落盘可恢复、旧澄清写路径移除。

- 套件：`cd tools/whiteboard && npm test` → **30 个测试文件、624 个测试全部
  通过**（record-00007 验收时为 557，净增 67）
- 覆盖率：语句 99.18%、分支 95.28%、函数 98.57%、行 99.64%（门槛 90%，
  基线 99.14/95.21/98.51/99.63，无回落）；新增的 `clarifyRules.ts`、
  `sessionTasks.ts` 满覆盖
- 类型检查与构建：`npm run typecheck` 无错误；`npm run build` 通过
- 契约测试（真实 `docs/` 零诊断）保持常绿
- 实现由三个子代理分工完成（W1 服务端裁决 / W2 指令契约 / W3 前端），GWT
  核验由未参与实现的第四个 subagent 完成（见结论）

测试名为 `tools/whiteboard/` 下的用例标题：`t/` = `test/`，`w/` = `web/test/`。

## 新增与改写的 GWT（spec 侧）

| GWT id | 测试 | 结果 |
| --- | --- | --- |
| spec-00001-AC-2.4 | offers only the editor and the relation list for a document with front matter problems (w/toolbar)——现断言含无答疑 | pass |
| spec-00001-AC-3.1 | offers edit, status, review, ask, and advance (w/toolbar) | pass |
| spec-00001-AC-9.1 | starts a clarify session on a draft of a clarifiable type (t/server)；starts a clarify session on one press (w/toolbar) | pass |
| spec-00001-AC-9.2 | answers 422 and starts nothing for a document that is not draft (t/server)；rejects a document that is not draft (t/workflow) | pass |
| spec-00001-AC-9.3 | leaves clarify out for a type the config gives no focus line (w/toolbar)；offers no clarify entry for a type the config gives no focus line (w/canvas) | pass |
| spec-00001-AC-9.4 | rejects a draft of a type that is not clarifiable (t/workflow)；refuses a draft of a type that is not clarifiable (t/docService) | pass |
| spec-00001-AC-14.7 | names the commit after the session kind (t/docService)；commits what an ask session wrote under docs (t/server) | pass |
| spec-00001-AC-14.8 | commits what a clarify session wrote under docs, and nothing outside it (t/server) | pass |
| spec-00001-AC-16.3 | reports a missing agent CLI in the terminal, with no commit and no state file (t/server) | pass |
| spec-00001-AC-16.4 | reports a missing agent CLI in the terminal, with no commit and no state file (t/server) | pass |
| spec-00001-AC-18.2 | answers 409 for an ask while a clarify session is running, leaving it alone (t/server)；disables advance, clarify, and ask while a session is running (w/toolbar) | pass |
| spec-00001-AC-19.2 | answers 409 and starts nothing when the target document was deleted (t/server) | pass |
| spec-00001-AC-45.1 | carries the target path and both its relation document paths, as paths only (t/sessionTasks) | pass |
| spec-00001-AC-45.2 | leaves the relation context out when the document has none (t/sessionTasks) | pass |
| spec-00001-AC-45.3 | states the questioning skeleton: one at a time, at most 4 options, the recommended one first (t/sessionTasks) | pass |
| spec-00001-AC-45.4 | asks the session to answer for itself whatever the documents or the repository settle (t/sessionTasks) | pass |
| spec-00001-AC-45.5 | states the closing: Open Questions, status stays draft, settled answers revise the body (t/sessionTasks) | pass |
| spec-00001-AC-46.1 | points at the state file from the session\`s own working directory, and says when to write it (t/sessionTasks) | pass |
| spec-00001-AC-46.2 | carries what was already answered, and asks for it not to be asked again (t/sessionTasks)；carries the progress an earlier session left, and asks that it not be asked again (t/docService) | pass |
| spec-00001-AC-46.3 | says nothing about recovering when no file was left behind (t/sessionTasks) | pass |
| spec-00001-AC-46.4 | commits what a clarify session wrote under docs, and nothing outside it (t/server)——断言 commit 文件清单不含 `.whiteboard/` | pass |
| spec-00001-AC-46.5 | says nothing about recovering from a file that is not valid JSON (t/sessionTasks)；reads a file that is not valid JSON as nothing (t/sessionTasks) | pass |
| spec-00001-AC-46.6 | drops the clarify state file the document was left with (t/docService)；accepts a document that has no clarify state file to drop (t/docService) | pass |
| spec-00001-AC-47.1 | allows a document of any type in any status (t/workflow)；starts an ask session on an active record (t/server)；starts an ask session from an active record node (w/toolbar) | pass |
| spec-00001-AC-47.2 | leaves the document and the history alone when an ask session wrote nothing (t/server) | pass |
| spec-00001-AC-47.3 | carries the target path and both its relation document paths (t/sessionTasks)；plans an ask session about a document of any type and status, with its context (t/docService) | pass |
| spec-00001-AC-47.4 | offers only the editor and the relation list for a document with front matter problems (w/toolbar)——含无答疑入口 | pass |
| spec-00001-AC-47.5 | rejects an anomalous document (t/workflow；t/docService 与 t/server 各有对应拒绝用例) | pass |
| spec-00001-AC-48.1 | carries one distinct focus line for each of the five clarifiable types (t/config)；plans a clarify session carrying the document, its context, and its type focus line (t/docService)——含他类型焦点行不入指令 | pass |
| spec-00001-AC-48.2 | rejects an empty focus line（含仅空白变体，t/config）；refuses to start on a blank or multi-line focus line (t/startup) | pass |
| spec-00001-AC-48.3 | starts and reports its address on a valid config (t/startup)；实测：真实配置下服务照常启动、可澄清类型呈现澄清入口 | pass |
| spec-00001-AC-48.4 | rejects a config missing the focus line (t/config)；refuses to start when a clarifiable type carries no focus line (t/startup) | pass |
| spec-00001-AC-48.5 | rejects a focus line given to a type that is not clarifiable (t/config) | pass |
| spec-00001-AC-48.6 | rejects a focus line carrying a newline (t/config) | pass |

## rule 侧 GWT 的承载映射

rule-00001 的新条目是流程规则，由 spec 侧测试与实测承载（plan-00009 W5 的
约定）：

| GWT id | 测试 | 结果 |
| --- | --- | --- |
| rule-00001-AC-11.1 | states the closing: Open Questions, status stays draft, settled answers revise the body (t/sessionTasks)；实测 (b)：确认的未决点落入 Open Questions、文档保持 draft | pass |
| rule-00001-AC-11.2 | states the closing: Open Questions, status stays draft, settled answers revise the body (t/sessionTasks)——「既定结论直接修订正文」是被断言的收尾行之一 | pass |
| rule-00001-AC-20.1 | allows a draft of every clarifiable type (t/workflow)——prd 在集合内 | pass |
| rule-00001-AC-20.2 | rejects a draft of a type that is not clarifiable (t/workflow)——record 为用例 | pass |
| rule-00001-AC-21.1 | plans an ask session about a document of any type and status, with its context (t/docService)；starts an ask session on an active record (t/server) | pass |
| rule-00001-AC-21.2 | leaves the document and the history alone when an ask session wrote nothing (t/server)；实测 (c)：答疑会话修订正文后 status 仍为 active | pass |
| rule-00001-AC-21.3 | states what the session may do: answer, discuss, revise docs, and never move a status (t/sessionTasks)；实测 (c)：对话结论落入正文并以 wb(ask) 留痕 | pass |

## 既有断言的预期更新（非回归）

按 plan-00009 验收路径第 3 条，预期变化只在旧澄清路径，实际与预期一致：

- 移除/改写：`t/workflow` 的 applyClarify 5 例（→ assertClarifiable/
  assertAskable）、`t/docService` 与 `t/server` 的 clarify review 用例
  （→ 非 accept 动作一律 422）、`w/toolbar` 的澄清对话框 3 例、
  `w/accessibility` 的澄清对话框 Radix 用例、`w/canvas` 的手动澄清用例、
  `t/acceptance.test.ts` 的 S3 澄清步（→ 真实澄清会话由替身 agent 写入
  Open Questions，commit 链 `init, edit, clarify, edit, accept` 保持）。
- **必须一字不改、已核实**：`AC-8.4`（带未决 Open Questions 的接收被拒）与
  `hasOpenQuestions` 的测试逐字未动——接收门禁（BR-12）不因澄清改写而变。
- 机械性夹具更新：11 处 web 测试配置桩补 `focus: {}`、4 处会话桩补
  `kind: 'advance'`（接口加宽，断言未变）。

## 实测核对（plan 验收路径第 4 条）

在本仓的一次性克隆上执行（同一内容与配置；不向工作分支写试验 commit），
agent CLI 以替身脚本充当——它捕获收到的任务指令并按脚本落盘，**答题内容由
脚本给出**；选择题在真实 CLI 终端里的渲染不在本次实测范围（spec 口径：契约
在指令，不在服从）。五个场景 **28/28 断言通过**：

- **(a) 断会话恢复——通过**。draft idea 发起澄清，替身答两题并把进度写入
  `.whiteboard/clarify/<id>.json` 后，**SIGKILL 杀掉服务进程**；进度文件在
  磁盘上完好（两条已答）；重启服务再次澄清，任务指令包含恢复段与两条已答
  原文（`Recover from the progress below` + 答案逐字在内）。首次指令含 idea
  焦点行（「值不值得做」）、无恢复段、以会话 cwd 视角指明状态文件路径
  （`../.whiteboard/clarify/<id>.json`）。
- **(b) 收尾落盘——通过**。会话把确认的未决点追加进 Open Questions（小节
  新建）、删除状态文件、文档保持 `draft`；产生**恰一个** commit，信息
  `wb(clarify): <id>`，文件清单只含该文档、不含 `.whiteboard/`。
- **(c) 答疑修订——通过**。对 `active` 的 record 发起答疑，结论行落入正文，
  status 逐字未动，commit 信息 `wb(ask): <id>`；指令声明会话性质并含
  「Never touch a status line」。
- **(d) 单会话互斥——通过**。澄清会话运行中：发起答疑 → 409，发起推进 →
  409，运行中的会话不受影响（仍 running）。入口在 UI 上的禁用由
  `w/toolbar`/`w/canvas`/`w/board` 单元测试承载，实测只验 API 层。
- **(e) 接收清理——通过**。带遗留状态文件的 draft 执行接收：促进为
  `active`、状态文件被删除、commit 信息 `wb(accept): <id>`。

收尾核对：克隆即弃（主仓 `docs/` 全程未动）；主仓 624 测试与契约测试全绿、
`git status` 干净。实测中一处工具性返工：替身脚本最初用 macOS 不存在的
`timeout` 捕获指令导致 4 条指令断言读到空文件，换 bash `read -t` 后全过——
是实测脚手架的缺陷，不是被测系统的。

## 观察项（不阻塞，留待后续）

1. **任务指令为英文**（与 advance 既有指令一致，避免代码库内双语分裂），
   spec 强制的字面元素（「推荐」标注、会话种类词「澄清/答疑」、
   `Open Questions`）按原文嵌入。若域主要求全中文指令，须连 advance 一并翻。
2. **design-00002 §6 的「对话框关闭后焦点回到触发元素」失去了测试对象**：
   它此前唯一的被测主体是澄清对话框（本轮废弃）。焦点陷阱用例已移到命令
   面板，但焦点回归断言在 jsdom 下对命令面板不可构造（触发键非 Radix
   DialogTrigger），据实弃测。该行为是 Radix 承诺、非本项目实现，风险低。
3. **状态文件内容无 schema 校验**：合法 JSON 即注入恢复段，字段形态
   （asked/pending）由指令约定、无代码校验——损坏 JSON 已按 FR-46 兜住，
   字段漂移未兜。
4. 指令中的 `../` 前缀假设 `docs/` 直接位于仓库根之下；`config.readAgentCwd`
   允许 `docs/<子目录>` 作 cwd，届时该前缀与文档相对路径都需换算——既存
   假设，本轮起对状态文件也生效，spec/design 均未写明。

## 结论

spec 侧 34 行、rule 侧 7 行 GWT 全部 pass；既有 AC 无回归（AC-8.4 与
hasOpenQuestions 逐字未动仍绿）；四道质量门全绿；实测五场景 28/28。
record-00001 中因语义改写记 `n/a` 的 5 行（AC-3.1、AC-9.1…9.4）已以新证据
重验回填为 pass。plan-00009 置 `resolved`。
