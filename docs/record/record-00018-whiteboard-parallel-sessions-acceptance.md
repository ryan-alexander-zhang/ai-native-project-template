---
id: record-00018-whiteboard-parallel-sessions-acceptance
type: record
status: active
parent: plan-00018-whiteboard-parallel-sessions
verifies: [spec-00003-whiteboard-parallel-sessions, spec-00001-AC-12.8, spec-00001-AC-18.1, spec-00001-AC-18.2, spec-00001-AC-18.3, spec-00001-AC-49.3, spec-00001-AC-49.4, spec-00001-AC-49.5, spec-00001-AC-49.7, spec-00001-AC-49.8, spec-00001-AC-49.11, spec-00001-AC-49.12, spec-00001-AC-54.4]
---

# 验收记录：并行 Agent 会话与会话面板

对 [plan-00018-whiteboard-parallel-sessions](../plan/plan-00018-whiteboard-parallel-sessions.md)
的验收。本轮交付整份 `spec-00003-whiteboard-parallel-sessions`（10 FR、55 AC）：
`max_sessions` 配置键与启动校验（T3）、多会话注册表与并发受理、id 保留、
terminated 第三态、带会话标识的 API（T4）、全局串行收尾队列与刷新合并（T5）、
等待输入判定（T6）、会话面板、逐会话终端、节点标记与结束通知（T7）、正常
关停收尾（T8）。清单按 AC 逐条列全；另为第十六轮改写语义后旧证据改记 n/a
的 `spec-00001` 各 AC 补新证据行（record-00001/00008/00009/00010 的对应行）。
测试路径相对 `tools/whiteboard/`。

## 质量门

- `npm test`：40 个文件、1079 个测试全部通过。
- `npm run typecheck`：`tsc --noEmit` 无错。
- `npm run test:coverage`：statements 99.27% / branches 95.45% /
  functions 98.63% / lines 99.65%，四项均高于 90% 门槛且不低于改动前，
  未调整任何阈值。

## 验收清单

| 被验 id | 测试 | 结果 |
| --- | --- | --- |
| spec-00003-AC-1.1 | starts an ask on another document while a clarify session runs (test/server.test.ts)；runs sessions on two different documents at once, both of them interactive (test/sessionManager.test.ts) | pass |
| spec-00003-AC-1.2 | keeps the output and the input of each session to itself (test/sessionManager.test.ts)；replays each session its own output to its own terminal (test/server.test.ts)；dials a channel of its own for each session (web/test/terminalSessions.test.tsx) | pass |
| spec-00003-AC-1.3 | gives two parallel advances of the same type different target ids (test/server.test.ts) | pass |
| spec-00003-AC-2.1 | answers 409 for an ask on the document a clarify session is running on (test/server.test.ts)；refuses a second session on the same document, naming the exclusion, and leaves the first alone (test/sessionManager.test.ts) | pass |
| spec-00003-AC-2.2 | refuses the same document again, adding no session (test/sessionManager.test.ts) | pass |
| spec-00003-AC-2.3 | starts a session on a document related to the one that has a session (test/server.test.ts) | pass |
| spec-00003-AC-2.4 | names this document own session as the reason its entries are locked (web/test/sessions.test.tsx)；disables advance, clarify, and ask while this document has a session (web/test/toolbar.test.tsx) | pass |
| spec-00003-AC-2.5 | starts a new session on a document whose session has ended (test/sessionManager.test.ts) | pass |
| spec-00003-AC-2.6 | refuses a second advance from the same source document (test/sessionManager.test.ts) | pass |
| spec-00003-AC-3.1 | refuses a start once the cap is reached, naming the cap, and leaves the running ones alone (test/sessionManager.test.ts)；disables the starting points of a free document while the cap is reached (web/test/toolbar.test.tsx) | pass |
| spec-00003-AC-3.2 | refuses again at the cap, adding no session (test/sessionManager.test.ts) | pass |
| spec-00003-AC-3.3 | starts a session at the cap once one of the running ones has ended (test/server.test.ts)；admits the next start once one of the capped sessions has ended (test/sessionManager.test.ts) | pass |
| spec-00003-AC-3.4 | rejects a cap that is not a positive integer, naming the key (test/config.test.ts) | pass |
| spec-00003-AC-3.5 | reads a missing cap as the default of three (test/config.test.ts) | pass |
| spec-00003-AC-3.6 | gives the last slot to whichever start got there first (test/sessionManager.test.ts) | pass |
| spec-00003-AC-3.7 | counts a spawn failure towards no cap and lists it as failed (test/sessionManager.test.ts) | pass |
| spec-00003-AC-3.8 | refuses a second session outright when the cap is one (test/server.test.ts)；keeps the panel entry and badge working at a cap of one (web/test/sessions.test.tsx) | pass |
| spec-00003-AC-4.1 | lists every session with its kind, document, state and start time (web/test/sessions.test.tsx)；reads every session the server holds (web/test/api.test.tsx) | pass |
| spec-00003-AC-4.2 | shows an empty state when no session has run (web/test/sessions.test.tsx)；keeps the session panel entry in the top bar with nothing running (web/test/canvas.test.tsx) | pass |
| spec-00003-AC-4.3 | shows the session and selects its document (web/test/sessions.test.tsx)；puts the asked-for session on the terminal (web/test/board.test.tsx) | pass |
| spec-00003-AC-4.4 | shows the session and says so when its document has left the board (web/test/sessions.test.tsx) | pass |
| spec-00003-AC-4.5 | reads the running count against the cap (web/test/sessions.test.tsx)；counts the running sessions against the cap the config declares (web/test/board.test.tsx) | pass |
| spec-00003-AC-4.6 | shows a session that failed to start as failed (web/test/sessions.test.tsx)；counts a spawn failure towards no cap and lists it as failed (test/sessionManager.test.ts) | pass |
| spec-00003-AC-4.7 | shows a session that exited non-zero as failed (web/test/sessions.test.tsx) | pass |
| spec-00003-AC-4.8 | names the agent when the config declares more than one (web/test/sessions.test.tsx) | pass |
| spec-00003-AC-4.9 | names no agent when the config declares exactly one (web/test/sessions.test.tsx) | pass |
| spec-00003-AC-5.1 | keeps each session own terminal alive across a switch and back (web/test/terminalSessions.test.tsx) | pass |
| spec-00003-AC-5.2 | wraps up a session nobody is watching, commit and history included (test/server.test.ts) | pass |
| spec-00003-AC-5.3 | stops the session it names and leaves the other one running (test/server.test.ts)；stops the session it is given and leaves the other one running (test/sessionManager.test.ts) | pass |
| spec-00003-AC-5.4 | opens the terminal on the session an advance starts (web/test/board.test.tsx) | pass |
| spec-00003-AC-5.5 | answers 404 for a session that has already ended, whatever else runs (test/server.test.ts)；offers no stop while the session on show has ended (web/test/terminalSessions.test.tsx) | pass |
| spec-00003-AC-5.6 | keeps the session on show when the graph is re-read (web/test/board.test.tsx) | pass |
| spec-00003-AC-5.7 | sends size frames only from the session on show (web/test/terminalSessions.test.tsx) | pass |
| spec-00003-AC-6.1 | marks a running session that has printed nothing for the threshold (test/sessionManager.test.ts)；shows the awaiting count beside the entry (web/test/sessions.test.tsx) | pass |
| spec-00003-AC-6.2 | takes the mark down as soon as the session speaks again (test/sessionManager.test.ts)；drops the count when a session answers and goes on printing (web/test/sessions.test.tsx) | pass |
| spec-00003-AC-6.3 | drops the mark when a waiting session ends (test/sessionManager.test.ts)；draws no badge once no session is waiting (web/test/sessions.test.tsx) | pass |
| spec-00003-AC-6.4 | never marks a session whose process has exited with its wrap-up still running (test/sessionManager.test.ts) | pass |
| spec-00003-AC-6.5 | marks every session that has gone quiet in the listing (test/server.test.ts)；counts both sessions when two are waiting (web/test/sessions.test.tsx) | pass |
| spec-00003-AC-7.1 | announces each session that has ended since the last reading (web/test/board.test.tsx) | pass |
| spec-00003-AC-7.2 | announces a session the user stopped as terminated (web/test/board.test.tsx) | pass |
| spec-00003-AC-7.3 | announces each session that has ended since the last reading (web/test/board.test.tsx) | pass |
| spec-00003-AC-7.4 | announces a session that failed to start (web/test/board.test.tsx) | pass |
| spec-00003-AC-8.1 | gives two sessions ending one after the other a commit each, staging only its own file (test/server.test.ts) | pass |
| spec-00003-AC-8.2 | makes one commit when only one of two sessions changed anything under docs (test/server.test.ts) | pass |
| spec-00003-AC-8.3 | folds two sessions ending in one batch into a single refresh signal (test/server.test.ts) | pass |
| spec-00003-AC-8.4 | keeps a user save and a session wrap-up in two commits, neither swallowing the other (test/server.test.ts) | pass |
| spec-00003-AC-8.5 | loses nothing when two sessions wrote the same third document, crediting the first to end (test/server.test.ts) | pass |
| spec-00003-AC-8.6 | loses nothing when both sessions wrote before either ended, letting the first sweep the batch (test/server.test.ts) | pass |
| spec-00003-AC-9.1 | lists every running session so a reconnecting board can find them all (test/server.test.ts)；holds two unattached sessions and replays each one its own output (test/sessionManager.test.ts)；opens the terminal on load on the newest running session (web/test/board.test.tsx) | pass |
| spec-00003-AC-9.2 | announces nothing for a session that had already ended before it looked (web/test/board.test.tsx) | pass |
| spec-00003-AC-9.3 | wraps up every running session, and the next boot lists none of them (test/server.test.ts)；does nothing on a second shutdown (test/server.test.ts)；wraps up every session on a shutdown even when one wrap-up throws (test/sessionManager.test.ts)；handles SIGTERM itself instead of being killed by it (test/startup.test.ts) | pass |
| spec-00003-AC-10.1 | marks the node and shows that session without selecting the node (web/test/sessions.test.tsx) | pass |
| spec-00003-AC-10.2 | marks a waiting session apart from a running one (web/test/sessions.test.tsx) | pass |
| spec-00003-AC-10.3 | drops the marker once the session has ended (web/test/sessions.test.tsx) | pass |
| spec-00003-AC-10.4 | adds no edge and no diagnostic to the graph (web/test/sessions.test.tsx) | pass |
| spec-00001-AC-12.8 | signals the end of a session that changed nothing (test/server.test.ts)；leaves another document starting points alone (web/test/sessions.test.tsx)；hands this document entries back when its session ends (web/test/sessions.test.tsx) | pass |
| spec-00001-AC-18.1 | answers 409 with the same-document reason while that document has a session (test/server.test.ts) | pass |
| spec-00001-AC-18.2 | answers 409 with the cap reason once the cap is reached (test/server.test.ts) | pass |
| spec-00001-AC-18.3 | answers 409 again for the same document, with no side effect (test/server.test.ts) | pass |
| spec-00001-AC-49.3 | lets a new session start on that document once the stuck one has been stopped (test/server.test.ts)；stops the session from the terminal panel and hands the entries back (web/test/canvas.test.tsx) | pass |
| spec-00001-AC-49.4 | answers 404 for a session id it does not know (test/server.test.ts)；refuses to stop a session it does not know or one that has ended (test/sessionManager.test.ts) | pass |
| spec-00001-AC-49.5 | names this document own session as the reason its entries are locked (web/test/sessions.test.tsx)；prefers no next step over both concurrency reasons (web/test/toolbar.test.tsx) | pass |
| spec-00001-AC-49.7 | offers no stop for a session that has already ended (web/test/panels.test.tsx)；offers no stop while the session on show has ended (web/test/terminalSessions.test.tsx) | pass |
| spec-00001-AC-49.8 | reopens the terminal through the session panel once the panel is put away (web/test/canvas.test.tsx) | pass |
| spec-00001-AC-49.11 | locks a free document starting points at the cap and says why (web/test/sessions.test.tsx) | pass |
| spec-00001-AC-49.12 | hands the starting points back when the total falls below the cap (web/test/sessions.test.tsx) | pass |
| spec-00001-AC-54.4 | lists a stopped session with the exit status it really had (test/server.test.ts)；lists a session that failed as it was recorded (web/test/history.test.tsx) | pass |
