---
id: record-00022-whiteboard-co-write-acceptance
type: record
status: active
parent: plan-00022-whiteboard-co-write
verifies: [spec-00006-whiteboard-co-write, rule-00001-BR-28, rule-00001-BR-29, rule-00001-BR-30, spec-00001-AC-19.3]
---

# 验收记录：共写会话——文档与 agent 的双栏工作区

对 [plan-00022-whiteboard-co-write](../plan/plan-00022-whiteboard-co-write.md)
的验收。本轮交付 `spec-00006` 整份（10 FR / 44 AC）与 `rule-00001-BR-28`
… `BR-30`（12 AC），交付范围合计 56 条（`rule-00001-BR-24`）：第五种
会话种类共写——任意 `draft` 文档（work item 亦可 `open`）的多轮协作
写作，新建共写模式先占槽再建档，材料段（粘贴/仓内 id/仓外路径/URL），
回合锁工作区（干净缓冲重载、脏缓冲保护、Source 视图只读），收束过滤
（认领路径豁免、集合取号、front matter 守卫），会话期状态锁与编辑器
旁路封堵，手改注记（行首非斜杠注入）。文档轮（T1 两份 design、T2 两份
spec 与配置注释的交接修订，各经审计）先行；实现由 Opus 子代理承担
（T3 服务端、T4 前端），经一轮独立代码评审（10 条确认发现 + 6 条
线下项，全部修复；其中两条为设计层缺陷，spec/design 同步据实校正）与
一轮独立收口核验（56 条 AC 逐条语义复核，结论 CLEAR-TO-RESOLVE，
另揭两处 design 文字滞后已修）。测试路径相对 `tools/whiteboard/`。

## 质量门

- `npm test`：47 个文件、1383 个测试全部通过，无 skip/only/todo。
- `npm run typecheck`：`tsc --noEmit` 无错。
- `npm run test:coverage`：statements 99.06% / branches 95.35% /
  functions 98.77% / lines 99.47%，四项均高于 90% 门槛，未调整任何
  阈值、未新增排除。
- 共写启动形态沿条目的交互式 `command`/`args` 原样 spawn、不追加任何
  权限旁路旗标，由测试断言（`spec-00006-AC-7.1` 行）；claude 在共写
  形态下的写域与授权交互实测（design-00001 §11.5 的接入门）留待首次
  真实会话冒烟，配置注释已载验证义务。
- T3 期间记两次未复现的环境性抖动：对临时端口的请求被本机 H2 数据库
  服务应答（`Response does not match the HTTP/1.1 protocol`），后续
  多次全量均绿——非代码缺陷，如实在案。

## 验收清单

| 被验 id | 测试 | 结果 |
| --- | --- | --- |
| spec-00006-AC-1.1 | starts a cowrite session on a draft integration document and tells it what it may write (test/server.test.ts)；names the target, its folder README, the write scope, and what the materials owe (test/cowrite.test.ts) | pass |
| spec-00006-AC-1.2 | is not offered on an anomalous node (web/test/cowrite.test.tsx) | pass |
| spec-00006-AC-1.3 | runs the second configured agent when the request names it (test/server.test.ts)；sends the agent the user picked (web/test/cowrite.test.tsx) | pass |
| spec-00006-AC-2.1 | files the document from its template, commits it, and starts the session on it (test/server.test.ts)；files the document from the template and commits it as a create (test/docService.test.ts) | pass |
| spec-00006-AC-2.2 | files nothing and starts nothing when the slug, the type or the id refuses the create (test/server.test.ts)；refuses a slug that is not lower-case hyphenated, and files nothing (test/docService.test.ts) | pass |
| spec-00006-AC-2.3 | files nothing and starts nothing when the slug, the type or the id refuses the create (test/server.test.ts)；refuses an id that is already taken on disk (test/docService.test.ts) | pass |
| spec-00006-AC-2.4 | files nothing and starts nothing when the slug, the type or the id refuses the create (test/server.test.ts)；refuses a type that is not a flow entry type (test/docService.test.ts) | pass |
| spec-00006-AC-2.5 | leaves the blank create path exactly as it was (test/server.test.ts)；leaves the blank mode exactly as it was (web/test/cowrite.test.tsx) | pass |
| spec-00006-AC-2.6 | files no document when the session cap refuses the create (test/server.test.ts)；takes the slot without spawning anything, and spawns only when the terminal is launched (test/sessionManager.test.ts) | pass |
| spec-00006-AC-2.7 | admits the same create once the running session has ended (test/server.test.ts) | pass |
| spec-00006-AC-3.1 | carries every kind of material into the first task input (test/server.test.ts)；carries a pasted text and a URL into the instruction (test/cowrite.test.ts) | pass |
| spec-00006-AC-3.2 | carries every kind of material into the first task input (test/server.test.ts)；carries an in-repo document id with the path it resolves to, and an outside path as given (test/cowrite.test.ts) | pass |
| spec-00006-AC-3.3 | starts the session with no materials segment when none was given (test/server.test.ts)；starts with no materials at all (web/test/cowrite.test.tsx) | pass |
| spec-00006-AC-4.1 | opens the target on its Source view beside the terminal (web/test/cowrite.test.tsx) | pass |
| spec-00006-AC-4.2 | reloads a clean buffer from the disk (web/test/cowrite.test.tsx) | pass |
| spec-00006-AC-4.3 | locks the buffer while the session is not awaiting input (web/test/cowrite.test.tsx) | pass |
| spec-00006-AC-4.4 | gives the buffer back when the session ends (web/test/cowrite.test.tsx) | pass |
| spec-00006-AC-4.5 | keeps a dirty buffer and says the disk moved (web/test/cowrite.test.tsx) | pass |
| spec-00006-AC-4.6 | leaves the preview switchable while the buffer is locked (web/test/cowrite.test.tsx) | pass |
| spec-00006-AC-4.7 | never clears a dirty buffer when the lock arrives (web/test/cowrite.test.tsx) | pass |
| spec-00006-AC-5.1 | commits a body-only save and hands its note to the next printable frame (test/server.test.ts)；writes the hand-edit note ahead of the next printable frame, and only once (test/sessionManager.test.ts) | pass |
| spec-00006-AC-5.2 | makes no collapse commit for a session whose only change was the owner's own save (test/server.test.ts)；lets the note die with a session the user never typed into again (test/sessionManager.test.ts) | pass |
| spec-00006-AC-5.3 | defers the note past a control-only frame without consuming it (test/sessionManager.test.ts)；defers the note past a slash command at the start of the line (test/sessionManager.test.ts) | pass |
| spec-00006-AC-5.4 | refuses a save in the window before the reload, rather than overwriting (web/test/cowrite.test.tsx) | pass |
| spec-00006-AC-6.1 | commits the target and restores a rewrite of another existing document (test/docService.test.ts)；commits the target and its new reference in one commit, restoring what fell outside (test/server.test.ts) | pass |
| spec-00006-AC-6.2 | deletes a new document of a type other than reference (test/docService.test.ts) | pass |
| spec-00006-AC-6.3 | drops only the reference whose number landed first, and lands the rest of the run (test/docService.test.ts)；filters a reference whose number a document that landed first has taken (test/docService.test.ts) | pass |
| spec-00006-AC-6.4 | puts the target's front matter status back and commits the body it wrote (test/docService.test.ts) | pass |
| spec-00006-AC-6.5 | neither restores nor stages the product another running advance is writing (test/docService.test.ts)；leaves what another running session wrote to that session (test/server.test.ts) | pass |
| spec-00006-AC-6.6 | leaves a path whose own commit failed exactly where it is (test/docService.test.ts) | pass |
| spec-00006-AC-6.7 | stages no deletion when the target is gone, and lands the rest of the scope (test/docService.test.ts) | pass |
| spec-00006-AC-7.1 | spawns the agent entry's interactive form unchanged, with no flag of its own added (test/sessionManager.test.ts) | pass |
| spec-00006-AC-7.2 | keeps a session running and taking keystrokes after a second denied outside read (test/sessionManager.test.ts) | pass |
| spec-00006-AC-7.3 | says that reading anything outside the repo goes through the agent's own permission mechanism (test/cowrite.test.ts) | pass |
| spec-00006-AC-8.1 | commits the target and its new reference in one commit, restoring what fell outside (test/server.test.ts)；commits the target and a well-formed new reference in one commit, the status kept (test/docService.test.ts) | pass |
| spec-00006-AC-8.2 | makes no commit when the session left nothing behind (test/docService.test.ts) | pass |
| spec-00006-AC-8.3 | commits the filtered changes the same way when the owner stops the session mid-write (test/server.test.ts) | pass |
| spec-00006-AC-8.4 | commits several well-formed references taking a contiguous run of numbers (test/docService.test.ts)；passes a run of well-formed references starting one above the highest existing number (test/cowrite.test.ts) | pass |
| spec-00006-AC-9.1 | answers 422 for an active document and starts nothing (test/server.test.ts)；refuses an active living document and says why (test/docService.test.ts) | pass |
| spec-00006-AC-9.2 | refuses a resolved work item (test/docService.test.ts) | pass |
| spec-00006-AC-10.1 | refuses a status change, an accept and an identity-moving save while the session runs (test/server.test.ts)；refuses a status change and an accept with doc-busy while the session runs (test/docService.test.ts) | pass |
| spec-00006-AC-10.2 | evaluates the review gate as usual once the cowrite session has ended (test/server.test.ts)；evaluates the review gates as usual once no session is running on the document (test/docService.test.ts) | pass |
| spec-00006-AC-10.3 | refuses a status change, an accept and an identity-moving save while the session runs (test/server.test.ts)；refuses a save carrying a status the agent moved, however the disk reads now (test/docService.test.ts) | pass |
| spec-00006-AC-10.4 | commits a body-only save and hands its note to the next printable frame (test/server.test.ts)；lets a body-only save through and commits it as an edit (test/docService.test.ts) | pass |
| rule-00001-AC-28.1 | commits the target and its new reference in one commit, restoring what fell outside (test/server.test.ts) | pass |
| rule-00001-AC-28.2 | keeps an open work item open, whatever the session wrote into its status line (test/docService.test.ts) | pass |
| rule-00001-AC-28.3 | carries every kind of material into the first task input (test/server.test.ts)；carries a pasted text, an in-repo id and a URL together (test/cowrite.test.ts) | pass |
| rule-00001-AC-29.1 | builds a plan for a draft report, carrying the target as it stands (test/docService.test.ts) | pass |
| rule-00001-AC-29.2 | builds a plan for an open work item, and remembers that status as the one to keep (test/docService.test.ts) | pass |
| rule-00001-AC-29.3 | refuses an active living document and says why (test/docService.test.ts) | pass |
| rule-00001-AC-29.4 | refuses a resolved work item (test/docService.test.ts) | pass |
| rule-00001-AC-30.1 | commits the target and a well-formed new reference in one commit, the status kept (test/docService.test.ts) | pass |
| rule-00001-AC-30.2 | commits the target and restores a rewrite of another existing document (test/docService.test.ts) | pass |
| rule-00001-AC-30.3 | deletes a new document of a type other than reference (test/docService.test.ts)；rejects a document of another type, a status that is not draft, and a non-canonical path (test/cowrite.test.ts) | pass |
| rule-00001-AC-30.4 | filters a reference whose id another document declares, and lands the rest (test/docService.test.ts)；rejects an id another document already declares (test/cowrite.test.ts) | pass |
| rule-00001-AC-30.5 | puts the target's front matter status back and commits the body it wrote (test/docService.test.ts) | pass |
| spec-00001-AC-19.3 | refuses with a doc-missing conflict when the target is gone from disk (test/docService.test.ts) | pass |

无未完成或未覆盖条目；交付范围内 44 + 12 条全部通过，另附
`spec-00001-AC-19.3`（本轮在 spec-00001 修订轮新增的条目，其 FR 不在
交付范围、行为随本轮实现，作为额外证据在案）。

## 备注

- 评审轮的两条设计层裁定（豁免集取认领路径、注记注入限行首非斜杠）已
  回写 `spec-00006-FR-5`/`FR-6` 与 design-00001 §11.3/§11.4，均标注
  据实校正与理由。
- 核验轮揭出的两处 design 文字滞后（§11.3 流程图节点、§11.4 旁路比对
  锚点）已同轮修正。
- `spec-00004` 经查无会话种类枚举、零改动收项（plan T2 原文已校正）。
