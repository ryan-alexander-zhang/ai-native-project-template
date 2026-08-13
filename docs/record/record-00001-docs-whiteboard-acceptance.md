---
id: record-00001-docs-whiteboard-acceptance
type: record
status: active
parent: plan-00001-docs-whiteboard-mvp
verifies: [spec-00001-docs-whiteboard, rule-00001-docs-workflow]
---

# 验收记录：Docs 白板 MVP

对 [plan-00001-docs-whiteboard-mvp](../plan/plan-00001-docs-whiteboard-mvp.md)
的验收。实现位于 `tools/whiteboard/`。

- 套件：`cd tools/whiteboard && npm test` → **15 个测试文件、253 个测试全部通过**
- 覆盖率：语句 98.83%、分支 95.95%、函数 98.25%、行 99.23%（门槛 90%）
- 类型检查：`npm run typecheck` 无错误；`npm run build` 通过
- 独立核验：由未参与实现的 subagent 按 spec 与 rule 逐条比对测试断言，其发现的
  缺口已补测（见下方「核验补测」）

「测试」列的名称为 `tools/whiteboard/` 下的用例标题，文件见括号：
`t/` = `test/`，`w/` = `web/test/`。

## spec-00001 验收清单

| GWT id | 测试 | 结果 | 证据 |
| --- | --- | --- | --- |
| spec-00001-AC-1.1 | makes one node per document and one edge per relation field (t/docRepository) | pass | 节点与边逐一断言 |
| spec-00001-AC-1.2 | places every node without overlapping (w/board) | pass | ELK 实跑，y 不相等 |
| spec-00001-AC-1.3 | leaves README and TEMPLATE files out of the graph (t/docRepository) | pass | 仅 1 个节点 |
| spec-00001-AC-1.4 | yields an empty graph for an empty docs tree (t/docRepository)；renders an empty canvas without error (w/canvas) | pass | 数据层与组件层各一 |
| spec-00001-AC-1.5 | takes the node title from the first H1 (t/docRepository) | pass | 标题取自 H1 |
| spec-00001-AC-2.1 | marks a document without front matter and labels it by path (t/docRepository) | pass | 异常节点以路径为 id，其余 ok |
| spec-00001-AC-2.2 | marks an edge pointing at an unknown id (t/docRepository)；marks an edge pointing at an unknown document (w/canvas) | pass | 边 ok=false + issue |
| spec-00001-AC-2.3 | marks a document whose id does not match the id format (t/docRepository) | pass | id 格式违规 |
| spec-00001-AC-2.4 | offers nothing for an anomalous document (t/workflow)；offers only the editor for a document with front matter problems (w/toolbar) | pass | 仅 Edit 存在 |
| spec-00001-AC-3.1 | offers edit, status, review, and advance (w/toolbar)；opens the toolbar for the node the user clicks (w/canvas) | pass | 四类入口 |
| spec-00001-AC-3.2 | closes the toolbar when the canvas background is clicked (w/canvas) | pass | 点空白关闭 |
| spec-00001-AC-4.1 | writes the edited content to disk (t/docService)；saves the edited content and commits it (t/server) | pass | 磁盘内容比对 |
| spec-00001-AC-5.1 | rejects a save whose base no longer matches (t/docService)；answers 409 (t/server) | pass | hash CAS |
| spec-00001-AC-5.2 | rejects a save whose base no longer matches (t/docService) | pass | 磁盘保留外部版本 |
| spec-00001-AC-5.3 | rejects a save whose file was deleted (t/docService) | pass | 409 |
| spec-00001-AC-6.1 | offers active but not open or resolved for a draft living doc (t/workflow)；lists only the legal target statuses (w/toolbar) | pass | 候选集断言 |
| spec-00001-AC-6.2 | offers open but not active for a draft work item (t/workflow) | pass | 候选集断言 |
| spec-00001-AC-6.3 | offers archived but not resolved or open for an active living doc (t/workflow) | pass | 候选集断言 |
| spec-00001-AC-6.4 | offers resolved and wontfix but not active for an open work item (t/workflow) | pass | 候选集断言 |
| spec-00001-AC-7.1 | rejects an illegal transition and leaves the file untouched (t/docService)；answers 422 (t/server) | pass | 文件不变 + commit 数不变 |
| spec-00001-AC-8.1 | accepts a draft living doc into active and commits it (t/docService) | pass | status: active |
| spec-00001-AC-8.2 | accepts a draft work item into open (t/docService) | pass | status: open |
| spec-00001-AC-8.3 | rejects accepting a document that is already active (t/docService) | pass | 422 |
| spec-00001-AC-8.4 | rejects accepting a draft that carries unresolved open questions (t/docService) | pass | 文件仍 draft |
| spec-00001-AC-9.1 | writes clarify questions and keeps the document draft (t/docService) | pass | 小节含条目 + draft |
| spec-00001-AC-9.2 | creates the section when the document has none (t/workflow) | pass | 文末建节 |
| spec-00001-AC-9.3 | writes every question given (t/docService) | pass | 三条全部写入 |
| spec-00001-AC-9.4 | rejects clarify on a document that is not draft (t/docService) | pass | 422 |
| spec-00001-AC-10.1 | offers exactly spec for a prd (t/workflow) | pass | 候选恰为 spec |
| spec-00001-AC-10.2 | offers both prd and spec for an idea (t/workflow)；lists every next-step candidate (w/toolbar) | pass | 两个候选全列 |
| spec-00001-AC-10.3 | offers nothing for a type the flow config does not carry (t/workflow)；says there is no next step and stays disabled (w/toolbar) | pass | 入口禁用 |
| spec-00001-AC-11.1 | runs the configured command as the session (t/sessionManager)；starts an advance the flow config allows (t/server) | pass | 真实 PTY 会话 |
| spec-00001-AC-11.2 | names the target type, the fixed id number, and the relation to the source (t/advance)；sends the task instruction as the first input (t/sessionManager) | pass | CLI 收到指令 |
| spec-00001-AC-12.1 | streams output as it is produced (t/sessionManager)；streams session output over the socket (t/server) | pass | 无需刷新 |
| spec-00001-AC-12.2 | forwards terminal input to the CLI (t/sessionManager) | pass | CLI 回显 got:ping |
| spec-00001-AC-12.3 | shows the end state and runs the exit hook (t/sessionManager) | pass | session ended with code 0 |
| spec-00001-AC-12.4 | commits the product and finds nothing wrong with it (t/server) | pass | 刷新后新节点出现 |
| spec-00001-AC-13.1 | starts the session under the working directory the flow config constrains it to (t/sessionManager) | pass | spawn 收到 cwd=<repo>/docs |
| spec-00001-AC-13.2 | 人工实测（见下方「AC-13.2 的人工验证」） | pass | 越界写被拒，文件未改 |
| spec-00001-AC-13.3 | advances an idea into a prd the agent writes (t/acceptance) | pass | docs 内写入落盘 |
| spec-00001-AC-14.1 | commits the edit naming the action and the document id (t/docService) | pass | wb(edit): <id> |
| spec-00001-AC-14.2 | leaves an unrelated dirty file out of the commit (t/docService) | pass | commit 仅含目标文件 |
| spec-00001-AC-14.3 | accepts a draft living doc into active and commits it (t/docService) | pass | wb(accept): <id> |
| spec-00001-AC-14.4 | commits every file a session touched under one advance commit (t/acceptance) | pass | 两文件一次 commit |
| spec-00001-AC-15.1 | refuses to start without a flow config, naming the path (t/startup) | pass | 进程 exit 1 + 路径 |
| spec-00001-AC-15.2 | refuses to start on an invalid flow config, naming the offending entry (t/startup) | pass | exit 1 + flow.idea[0].next |
| spec-00001-AC-16.1 | reports a CLI missing from PATH in the terminal (t/sessionManager) | pass | 终端错误文本 |
| spec-00001-AC-16.2 | leaves no commit behind when the agent CLI never starts (t/server) | pass | commit 数不变 |
| spec-00001-AC-17.1 | marks a product that does not point back at its source (t/server, t/acceptance) | pass | node.ok=false |
| spec-00001-AC-17.2 | commits the product and finds nothing wrong with it (t/server)；advances an idea into a prd (t/acceptance) | pass | 节点正常 + parent 边 |
| spec-00001-AC-18.1 | refuses a second session and leaves the running one alone (t/sessionManager)；answers 409 while a session is running (t/server) | pass | 409 + 原会话仍 running |
| spec-00001-AC-19.1 | rejects an action on a document whose file was deleted, without committing (t/docService) | pass | 提示刷新 + 无 commit |
| spec-00001-AC-20.1 | reports the error and keeps the written file (t/docService) | pass | 清空 git 身份，文件保留 |
| spec-00001-AC-21.1 | keeps writing files after the last terminal detaches (t/sessionManager) | pass | 断连后产出仍落盘 |
| spec-00001-AC-21.2 | replays what the session already printed to a reconnecting terminal (t/server)；opens the terminal on load when a session is still running (w/board) | pass | 缓冲回放 |

## rule-00001 验收清单

| GWT id | 测试 | 结果 | 证据 |
| --- | --- | --- | --- |
| rule-00001-AC-1.1 | the config shipped with this repo (t/config) | pass | idea/prd/spec = living |
| rule-00001-AC-1.2 | the config shipped with this repo (t/config) | pass | issue/plan/task = work |
| rule-00001-AC-2.1 … AC-9.2 | allowedTransitions 的十条用例 (t/statusRules) | pass | 流转表逐行 + otherwise |
| rule-00001-AC-10.1 | promotes a living doc to active (t/statusRules)；accepts a draft living doc into active (t/docService) | pass | draft → active |
| rule-00001-AC-10.2 | promotes a work item to open (t/statusRules)；accepts a draft work item into open (t/docService) | pass | draft → open |
| rule-00001-AC-11.1 | writes clarify questions and keeps the document draft / writes every question given (t/docService) | pass | 多条写入 + 仍 draft |
| rule-00001-AC-12.1 | promotes a draft whose open questions section is gone (t/workflow) | pass | 促进成功 |
| rule-00001-AC-12.2 | rejects a draft carrying unresolved open questions (t/workflow) | pass | 促进被拒 |
| rule-00001-AC-13.1 | offers both prd and spec for an idea (t/workflow) | pass | 候选 + carry: parent |
| rule-00001-AC-14.1 | offers exactly spec for a prd (t/workflow) | pass | 候选 + carry: parent |
| rule-00001-AC-15.1 | offers rule, design, and plan for a spec (t/workflow) | pass | 三候选 |
| rule-00001-AC-15.2 | advances a spec into a rule carrying informs and a plan carrying implements (t/acceptance) | pass | 产出以 informs 回指 |
| rule-00001-AC-15.3 | 同上 (t/acceptance) | pass | 产出以 implements 指向 |
| rule-00001-AC-16.1 | offers task for a plan, carrying parent (t/workflow) | pass | 候选 + carry |
| rule-00001-AC-17.1 | offers nothing for a type the flow config does not carry (t/workflow) | pass | record 无下一步 |
| rule-00001-AC-18.1 | takes the next number after the highest in use (t/workflow) | pass | prd-00002- |
| rule-00001-AC-18.2 | starts at one for a type with no documents (t/workflow) | pass | task-00001- |
| rule-00001-AC-19.1 | 无（按设计未实现） | n/a | 见下方「按设计未强制的规则」 |
| rule-00001-AC-19.2 | 无（按设计未实现） | n/a | 同上 |

## AC-13.2 的人工验证

该 AC 断言的是所选 CLI 的权限机制（白板只负责传递约束，见 FR-13），无法用测试
替身证明，按 design §3 作为接入 CLI 的前置实测执行：

- 被测：Claude Code 2.1.229（出厂配置中的 `claude`）
- 夹具：临时仓库含 `docs/prd/a.md` 与 `src/app.ts`（内容 `placeholder`）
- 过程：以 `cwd=docs`、`--permission-mode acceptEdits`（比出厂配置的 `args: []`
  更宽松——它自动接受编辑）运行，指令要求同时写 `../src/app.ts` 与 `./prd/note.md`
- 结果：**通过**。CLI 拒绝越界写并说明"outside this session's allowed working
  directory"；`src/app.ts` 仍为 `placeholder`，而 `docs/prd/note.md` 正常写入。
  出厂配置使用默认权限模式（还会向人询问），约束不弱于本次实测。

结论：`claude` 满足 AC-13.2，可留在出厂 `whiteboard.config.yaml`。**接入任何新
CLI 前必须重跑此实测**，未通过者不得进入出厂配置。

## 按设计未强制的规则

`rule-00001-BR-19`（文档处于 `archived` 的前提是存在 `supersedes` 它的替代文档）
是业务规则，但 MVP 不由软件强制——[spec-00001](../spec/spec-00001-docs-whiteboard.md)
§6 Out of Scope 明确豁免归档配对检查，白板只保证流转合法。因此
`rule-00001-AC-19.1` 与 `AC-19.2` 无对应实现与测试，记为**按设计未实现**，非缺陷。
若后续版本要强制该规则，需同时修订 spec §6 并补测。

## 核验补测

独立核验发现并已补齐的缺口（提交 `a9c92e4`）：

- AC-15.1/15.2 此前只验到 `loadFlowConfig` 层，未覆盖真正"拒绝启动"的
  `bin/whiteboard.js` → 新增 `t/startup` 以子进程实跑，断言 exit 1 与错误内容
- AC-13.1 无直接断言 → 新增以替身 spawn 断言会话 cwd 即配置约束
- AC-21.1 只断言会话仍 running，未断言"后续产出照常落盘" → 补断言
- AC-16.2 断言的是 onExit 未调用而非"无 commit" → 在 API 层补 commit 数断言
- AC-14.4 只用单文件证明"全部变更" → 补多文件单 commit 用例
- rule-00001-AC-1.1/1.2、AC-15.2/15.3 缺断言 → 补出厂配置 kind 断言与
  informs/implements 推进端到端用例
- AC-1.4 缺组件层空画布、AC-2.4 漏断言 Clarify 不出现 → 补齐
- 若干测试的 AC id 注释过宽（覆盖范围小于所标 id）→ 已收窄，避免日后被当作唯一证据
