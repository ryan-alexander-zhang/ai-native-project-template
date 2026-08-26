---
id: record-00021-whiteboard-ask-threads-acceptance
type: record
status: active
parent: plan-00021-whiteboard-ask-threads
verifies: [spec-00005-whiteboard-ask-threads]
---

# 验收记录：答疑线程——问完即走的只读问答

对 [plan-00021-whiteboard-ask-threads](../plan/plan-00021-whiteboard-ask-threads.md)
的验收。本轮交付 `spec-00005` 整份（9 FR / 41 AC，`rule-00001-BR-24` 的
交付范围 = 整份 spec）：终端答疑退役，答疑改为 headless 只读形态——
双入口共用问题输入、每问一条独立会话、追问 resume、问题列表为编辑器
第三视图态、不占文档、无 commit、结束经既有通知通路送达。文档轮
（T1 两份 design 修订、T2 五份既有文档交接）先行完成；实现由 Opus
子代理承担、各经一轮独立代码评审（T3 十条、T4 八条发现，全部修复或
经域主裁决）。测试路径相对 `tools/whiteboard/`。

## 质量门

- `npm test`：44 个文件、1208 个测试全部通过。
- `npm run typecheck`：`tsc --noEmit` 无错。
- `npm run test:coverage`：statements 99.15% / branches 95.26% /
  functions 98.96% / lines 99.55%，四项均高于 90% 门槛，未调整任何
  阈值、未新增排除。
- claude headless 声明经 design-00001 §10.1 的四项实测门（JSON 单
  对象、`.result`/`.session_id`、`--resume` 延续上下文、
  `--permission-mode plan` 只读拦写）实测通过后进入模板配置。
- 核验轮记一次未复现的环境性抖动：首跑一例对 loopback 的请求收到
  外部注入的 `Forbidden` 响应体（仓库内无此字符串），单跑与后续两次
  全量均绿——非代码缺陷，如实在案。

## 验收清单

| 被验 id | 测试 | 结果 |
| --- | --- | --- |
| spec-00005-AC-1.1 | starts a headless first call carrying the paths, the read-only nature and the question (test/server.test.ts) | pass |
| spec-00005-AC-1.2 | plans a first call carrying the document, its relation paths and the question (test/docService.test.ts)；carries the target path and both its relation document paths (test/sessionTasks.test.ts) | pass |
| spec-00005-AC-1.3 | starts a call on an active record like any other document (test/server.test.ts)；allows a document of any type in any status (test/workflow.test.ts)；opens the question input from an active record node (web/test/toolbar.test.tsx) | pass |
| spec-00005-AC-2.1 | resumes the thread on a follow-up, carrying the question alone (test/server.test.ts)；plans a follow-up carrying the question alone, with the thread's resume id (test/docService.test.ts) | pass |
| spec-00005-AC-2.2 | opens a second thread for a new question, whose first call resumes nothing (test/server.test.ts) | pass |
| spec-00005-AC-2.3 | offers only the agents that declare a headless form, and keeps a thread on its own (test/server.test.ts)；narrows the choice to the agents that declare a headless form · puts the question to the headless agent the user picked (web/test/agents.test.tsx) | pass |
| spec-00005-AC-3.1 | shows an answered thread with its answer rendered as Markdown (web/test/ask.test.tsx) | pass |
| spec-00005-AC-3.2 | shows a thread whose call is in flight as running (web/test/ask.test.tsx) | pass |
| spec-00005-AC-3.3 | restores a call in flight on a fresh page and carries its answer in (web/test/ask.test.tsx) | pass |
| spec-00005-AC-3.4 | opens no terminal when a question is submitted (web/test/ask.test.tsx) | pass |
| spec-00005-AC-3.5 | falls back to the terminal-form session, never to the newer ask (web/test/ask.test.tsx) | pass |
| spec-00005-AC-4.1 | leaves the document and the repository untouched when a call finishes (test/server.test.ts) | pass |
| spec-00005-AC-4.2 | runs the declared read-only flags on the actual command line (test/server.test.ts) | pass |
| spec-00005-AC-4.3 | commits nothing when it ends before an advance that has written under docs (test/server.test.ts) | pass |
| spec-00005-AC-5.1 | serves the questions and answers of an earlier run, and resumes from them (test/server.test.ts) | pass |
| spec-00005-AC-5.2 | keeps the list out of git and out of the docs tree (test/server.test.ts) | pass |
| spec-00005-AC-5.3 | writes off a call the last process was killed with, at the next boot (test/server.test.ts) | pass |
| spec-00005-AC-5.4 | records a call the shutdown stopped as terminated, ready to be resent (test/server.test.ts) | pass |
| spec-00005-AC-5.5 | writes a history entry whose transcript is the captured answer (test/server.test.ts) | pass |
| spec-00005-AC-6.1 | starts a call on a document an advance session is running on (test/server.test.ts) | pass |
| spec-00005-AC-6.2 | starts an advance on a document a call is running on (test/server.test.ts) | pass |
| spec-00005-AC-6.3 | runs two threads of the same document at once (test/server.test.ts) | pass |
| spec-00005-AC-6.4 | refuses a call at the cap and appends nothing to the list (test/server.test.ts) | pass |
| spec-00005-AC-6.5 | never reads a silent call as waiting on the user (test/server.test.ts) | pass |
| spec-00005-AC-6.6 | announces the end of an ask like any other session (web/test/notifications.test.tsx) | pass |
| spec-00005-AC-7.1 | refuses a second submit on a thread whose call is running (test/server.test.ts)；takes no follow-up while that thread has a call running (web/test/ask.test.tsx) | pass |
| spec-00005-AC-7.2 | refuses a call on an anomalous document and starts nothing (test/server.test.ts)；refuses an anomalous document (test/docService.test.ts)；rejects an anomalous document (test/workflow.test.ts) | pass |
| spec-00005-AC-7.3 | offers no question entry in an anomalous document editor (web/test/ask.test.tsx) | pass |
| spec-00005-AC-7.4 | refuses a call when no agent declares a headless form (test/server.test.ts)；draws neither ask entry when no agent declares a headless form (web/test/agents.test.tsx)；draws no ask entry when no agent declares a headless form (web/test/toolbar.test.tsx) | pass |
| spec-00005-AC-7.5 | marks a call that failed, keeps the rest of the thread, and resends into a new call (test/server.test.ts)；resends a failed question on its own thread (web/test/ask.test.tsx) | pass |
| spec-00005-AC-7.6 | stops a running ask from its own panel row (web/test/ask.test.tsx) | pass |
| spec-00005-AC-7.7 | refuses terminal attach, input and resize on a call (test/server.test.ts) | pass |
| spec-00005-AC-8.1 | rejects a headless declaration missing a required placeholder (test/config.test.ts) | pass |
| spec-00005-AC-8.2 | runs the declared first form and then the declared resume form (test/server.test.ts) | pass |
| spec-00005-AC-9.1 | lists every thread with its state and opens one on its questions and answers (web/test/ask.test.tsx) | pass |
| spec-00005-AC-9.2 | opens the located thread and leaves the unsaved buffer alone (web/test/notifications.test.tsx) | pass |
| spec-00005-AC-9.3 | opens the located thread from the session panel row (web/test/ask.test.tsx) | pass |
| spec-00005-AC-9.4 | refuses and leaves the view alone when the document has left the board (web/test/notifications.test.tsx) | pass |
| spec-00005-AC-9.5 | refuses and leaves the view alone when the session is gone (web/test/notifications.test.tsx) | pass |
| spec-00005-AC-9.6 | draws one marker and shows the terminal-form session (web/test/ask.test.tsx) | pass |
| spec-00005-AC-9.7 | opens the ask list from a document whose only session is an ask (web/test/ask.test.tsx) | pass |

## 附注

- 交接守卫 `spec-00001-AC-47.1`（答疑不启动终端形态会话）由
  `spec-00005-AC-1.1` 与 `AC-3.4` 的测试共同覆盖，`AC-1.1` 的服务端
  测试已加其共注；退役的 `spec-00001-AC-47.2` … `AC-47.5`、
  `AC-14.7` 与 `rule-00001-AC-21.3` 在测试树中无残留断言，两处过时的
  源码注释引用已随本轮校正。
- `spec-00005-FR-9` 的「浮窗答疑入口」导航半句经域主裁定勘误
  （2026-08-26）：浮窗是提问入口（FR-1），提交即收起不跳转。
