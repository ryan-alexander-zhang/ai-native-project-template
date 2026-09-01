---
id: record-00023-doc-annotations-acceptance
type: record
status: active
parent: plan-00023-doc-annotations
verifies: [spec-00007-doc-annotations]
---

# 验收记录：文内标注——选区标注、统一提交与双通路进展

对 [plan-00023-doc-annotations](../plan/plan-00023-doc-annotations.md) 的验收。
本轮交付 `spec-00007` 整份（12 FR / 72 AC，`rule-00001-BR-24`）：编辑与预览
两侧的选区加注与不可映射区守门，内容锚的恰一处命中（键含上下文、创建期歧义同
拦），板外键控存储与改删/重新选区，统一提交的整体前置（未保存缓冲、空提交、
在途、盘上已删或改 id）与逐条锚校验、双通路分派（先共写后 question）与逐通路
agent 缺省，question 通路的答疑首调组装，issue 通路的 `active → draft` 自动
流转与一个共写会话（全有或全无以流转写盘为界），批状态生命周期与异常终止核销，
标注列表的逐项进展、定位高亮与「原文已变更」退化。文档轮（T1 两份 design、
T2 五份既有文档的交接修订）先行且各经审计；实现分 T3 服务端与 T4 前端。
测试路径相对 `tools/whiteboard/`。

`spec-00007` 不新增业务规则（§3），故本记录的 `verifies` 只列该 spec；
`rule-00001-BR-28` 的材料给法枚举扩展属 T2 交接修订，其行为由
`spec-00007-AC-7.1` 的材料段断言一并覆盖。

## 质量门

- `npm test`：55 个文件、1660 个测试全部通过，无 skip/only/todo（T4 收口时
  1650，本轮新增 10 条：8 条服务端集成 + 2 条前端集成）。
- `npm run typecheck`：`tsc --noEmit` 无错。
- `npm run test:coverage`：statements 98.62% / branches 95.27% /
  functions 98.41% / lines 99.19%，三项门槛（lines / branches / functions
  各 90%）均通过，未调整任何阈值、未新增排除。
- 本轮只动测试文件（`test/annotations.test.ts`、`test/server.test.ts`、
  `web/test/annotationBoard.test.tsx`），无产品代码改动。

## 验收清单

| 被验 id | 测试 | 结果 |
| --- | --- | --- |
| spec-00007-AC-1.1 | records the type, the text, the anchor, the quote and the moment (test/annotations.test.ts)；records an annotation and serves it back with where its anchor lands (test/server.test.ts)；records the type, the text and the anchor (web/test/annotate.test.tsx)；annotates a selection made in the editor (web/test/annotationEditor.test.tsx)；cuts the anchor and its context from the whole file (web/test/annotationMapping.test.tsx) | pass |
| spec-00007-AC-1.2 | maps a selection to the source interval it came from (web/test/annotationMapping.test.tsx) | pass |
| spec-00007-AC-1.3 | records an annotation on text that is not on disk at all (test/annotations.test.ts)；annotates text that has not been saved (web/test/annotationEditor.test.tsx)；anchors a selection over unsaved text (web/test/annotationMapping.test.tsx) | pass |
| spec-00007-AC-1.4 | refuses an empty text, and one that is only whitespace (test/annotations.test.ts)；answers 422 with the reason for an ineligible type and for an empty text (test/server.test.ts)；refuses an empty annotation text (web/test/annotate.test.tsx) | pass |
| spec-00007-AC-1.5 | leaves the event alone with nothing selected (web/test/annotate.test.tsx)；offers nothing with no selection (web/test/annotationEditor.test.tsx)；refuses an empty selection (web/test/annotationMapping.test.tsx) | pass |
| spec-00007-AC-1.6 | leaves the event alone over an unannotatable region (web/test/annotate.test.tsx)；offers nothing over the front matter (web/test/annotationEditor.test.tsx)；refuses a selection inside a code block (web/test/annotationMapping.test.tsx)；refuses the front matter and the code regions (web/test/annotationMapping.test.tsx) | pass |
| spec-00007-AC-2.1 | finds the sentence again after two paragraphs are inserted before it (test/annotationAnchor.test.ts) | pass |
| spec-00007-AC-2.2 | fails as missing when the sentence has been deleted (test/annotationAnchor.test.ts) | pass |
| spec-00007-AC-2.3 | fails as ambiguous when a later edit gave the whole key a second place (test/annotationAnchor.test.ts)；fails as ambiguous when the context is gone and the sentence stands twice (test/annotationAnchor.test.ts)；holds back an annotation whose passage now stands in two places (test/annotations.test.ts) | pass |
| spec-00007-AC-2.4 | fails as ambiguous when the whole key stood twice from the start (test/annotationAnchor.test.ts) | pass |
| spec-00007-AC-3.1 | changes one annotation and drops another, keeping the rest (test/annotationStore.test.ts)；changes, re-anchors and drops an annotation (test/server.test.ts)；brings the annotations and their states back across a restart (test/server.test.ts)；edits and drops an annotation (web/test/annotationBoard.test.tsx) | pass |
| spec-00007-AC-3.2 | keeps the file out of git, and out of the docs tree (test/annotationStore.test.ts) | pass |
| spec-00007-AC-3.3 | reads back what it wrote, with the quote derived from the anchor (test/annotationStore.test.ts)；brings the annotations and their states back across a restart (test/server.test.ts) | pass |
| spec-00007-AC-3.4 | replaces the anchor and the quote on a re-anchor, and clears the orphan mark (test/annotationStore.test.ts)；clears the last submit’s reason when the annotation is changed (test/annotationStore.test.ts)；changes, re-anchors and drops an annotation (test/server.test.ts)；offers the first completed selection as the new anchor (web/test/annotate.test.tsx)；re-anchors an orphan onto a new selection (web/test/annotationBoard.test.tsx) | pass |
| spec-00007-AC-4.1 | withholds the issue type on a resolved plan (test/annotations.test.ts)；reads a <status> <type> as annotatable-with-an-issue or not — eight parameterised cases (test/workflow.test.ts)；shows the issue entry disabled with its reason when the status gate is shut (web/test/annotate.test.tsx) | pass |
| spec-00007-AC-4.2 | refuses an issue on an archived document, and takes a question (test/annotations.test.ts)；answers 422 with the reason for an ineligible type and for an empty text (test/server.test.ts) | pass |
| spec-00007-AC-4.3 | offers both types on a draft document (test/annotations.test.ts)；reads a <status> <type> as annotatable-with-an-issue or not — eight parameterised cases (test/workflow.test.ts)；offers both types when both gates are open (web/test/annotate.test.tsx) | pass |
| spec-00007-AC-4.4 | offers both types on an open plan, with no transition to make (test/annotations.test.ts)；reads a <status> <type> as annotatable-with-an-issue or not — eight parameterised cases (test/workflow.test.ts)；offers both types when both gates are open (web/test/annotate.test.tsx) | pass |
| spec-00007-AC-4.5 | withholds the issue type on a wontfix issue document (test/annotations.test.ts)；reads a <status> <type> as annotatable-with-an-issue or not — eight parameterised cases (test/workflow.test.ts)；shows the issue entry disabled with its reason when the status gate is shut (web/test/annotate.test.tsx) | pass |
| spec-00007-AC-4.6 | refuses either type on a document whose front matter will not read (test/annotations.test.ts)；withholds both types on an anomalous document (test/annotations.test.ts)；refuses the whole submit on an anomalous document (test/annotations.test.ts)；reads an anomalous document as annotatable with nothing at all (test/workflow.test.ts)；offers nothing at all when both gates are shut (web/test/annotate.test.tsx) | pass |
| spec-00007-AC-4.7 | holds back every issue when the document lost its eligibility, and submits the question (test/annotations.test.ts) | pass |
| spec-00007-AC-5.1 | submits both types and leaves the unsubmitted region empty (test/annotations.test.ts)；submits both paths at once: the transition, the cowrite, and a thread per question (test/server.test.ts) | pass |
| spec-00007-AC-5.2 | fails as missing when the sentence has been rewritten (test/annotationAnchor.test.ts)；holds back only the annotation whose passage was rewritten (test/annotations.test.ts) | pass |
| spec-00007-AC-5.3 | answers an empty list for a document nobody has annotated, and refuses an empty submit (test/annotations.test.ts)；answers 422 for an empty submit and for an unsaved buffer (test/server.test.ts)；is out only when there is nothing unsubmitted (web/test/annotationList.test.tsx) | pass |
| spec-00007-AC-5.4 | refuses the whole submit when the buffer is unsaved, starting nothing (test/annotations.test.ts)；answers 422 for an empty submit and for an unsaved buffer (test/server.test.ts)；refuses the submit while the buffer is unsaved (web/test/annotationBoard.test.tsx)；refuses the press while the buffer is unsaved (web/test/annotationList.test.tsx) | pass |
| spec-00007-AC-5.5 | defaults each path to the first agent of its own set (test/annotations.test.ts)；offers each path its agent and defaults to the first of its set (web/test/annotationList.test.tsx) | pass |
| spec-00007-AC-5.6 | defaults each path to the first agent of its own set (test/annotations.test.ts)；names nobody for a path whose set holds one agent (web/test/annotationList.test.tsx) | pass |
| spec-00007-AC-5.7 | states one ask, a cowrite and the transition for a question and two issues (test/annotations.test.ts)；states what the submit will do, line by line (web/test/annotationList.test.tsx) | pass |
| spec-00007-AC-5.8 | gives the last slot to the cowrite and holds back the questions for the cap (test/annotations.test.ts) | pass |
| spec-00007-AC-6.1 | opens one thread per question, each with its own passage (test/annotations.test.ts)；submits both paths at once: the transition, the cowrite, and a thread per question (test/server.test.ts)；carries the marked passage after the standing instruction, when there is one (test/sessionTasks.test.ts) | pass |
| spec-00007-AC-6.2 | opens one thread per question, each with its own passage (test/annotations.test.ts) | pass |
| spec-00007-AC-6.3 | holds back a single question the cap refuses and submits the rest (test/annotations.test.ts) | pass |
| spec-00007-AC-7.1 | shows the context whole with the selection fenced in double brackets (test/annotationAnchor.test.ts)；moves an active document to draft in its own commit, then starts one cowrite (test/annotations.test.ts)；gives each issue its number, its marked passage with context, and what is wanted (test/cowrite.test.ts)；adds the four discipline clauses after the issues and before the closing line (test/cowrite.test.ts)；submits both paths at once: the transition, the cowrite, and a thread per question (test/server.test.ts) | pass |
| spec-00007-AC-7.2 | starts the cowrite straight away on a draft, with no transition commit (test/annotations.test.ts) | pass |
| spec-00007-AC-7.3 | submits both types and leaves the unsubmitted region empty (test/annotations.test.ts)；submits both paths at once: the transition, the cowrite, and a thread per question (test/server.test.ts) | pass |
| spec-00007-AC-7.4 | leaves an active document untouched when the agent could not be started (test/annotations.test.ts)；answers exactly what the spawner answers, for a command that runs and two that do not (test/annotations.test.ts) | pass |
| spec-00007-AC-7.5 | starts the session when the transition wrote but could not commit (test/annotations.test.ts)；reports a transition whose commit failed as a notice (web/test/annotationBoard.test.tsx) | pass |
| spec-00007-AC-8.1 | refuses an advance and an accept on the document its own annotations are being cowritten on (test/server.test.ts) | pass |
| spec-00007-AC-8.2 | restores what the annotation-started session wrote outside the target and commits the rest (test/server.test.ts) | pass |
| spec-00007-AC-8.3 | reads the annotation-started session as awaiting input once it has gone quiet (test/server.test.ts) | pass |
| spec-00007-AC-8.4 | refuses an advance and an accept on the document its own annotations are being cowritten on (test/server.test.ts) | pass |
| spec-00007-AC-8.5 | admits the session on the status the transition left, not the one it started from (test/annotations.test.ts)；makes exactly the transition and the collapse commit, leaving the document on draft (test/server.test.ts) | pass |
| spec-00007-AC-8.6 | keeps the preview on show and switches the terminal to the session (web/test/annotationBoard.test.tsx) | pass |
| spec-00007-AC-9.1 | opens the question list on the thread of a question row (web/test/annotationBoard.test.tsx)；shows each annotation’s type, text, quote and state (web/test/annotationList.test.tsx)；reads each part off the payload that owns it (web/test/annotationMapping.test.tsx) | pass |
| spec-00007-AC-9.2 | opens the question list on the thread of a question row (web/test/annotationBoard.test.tsx)；goes to the thread of a submitted question (web/test/annotationList.test.tsx) | pass |
| spec-00007-AC-9.3 | shows the cowrite session of a batch being cowritten (web/test/annotationBoard.test.tsx)；shows the session of a batch being cowritten (web/test/annotationList.test.tsx) | pass |
| spec-00007-AC-9.4 | records a natural end as done with its commit, and keeps the annotations submitted (test/annotationStore.test.ts)；records a natural end as done, with the collapse commit (test/annotations.test.ts)；shows a finished batch’s commit as a short hash (web/test/annotationList.test.tsx)；carries a finished batch’s commit, or says there was no change (web/test/annotationMapping.test.tsx) | pass |
| spec-00007-AC-9.5 | records a natural end with nothing committed as done with no commit (test/annotationStore.test.ts)；records a natural end with nothing committed as done with no commit (test/annotations.test.ts)；says a finished batch landed no change (web/test/annotationList.test.tsx)；carries a finished batch’s commit, or says there was no change (web/test/annotationMapping.test.tsx) | pass |
| spec-00007-AC-9.6 | locates in the view state the reader was last on (web/test/annotationBoard.test.tsx)；marks the passage in the editor (web/test/annotationEditor.test.tsx) | pass |
| spec-00007-AC-9.7 | keeps an unsaved buffer across the list and back (web/test/annotationEditor.test.tsx) | pass |
| spec-00007-AC-9.8 | brings the annotations and their states back across a restart (test/server.test.ts)；shows each annotation’s type, text, quote and state (web/test/annotationList.test.tsx) | pass |
| spec-00007-AC-9.9 | reads a document with no file at all as an empty list (test/annotationStore.test.ts)；answers an empty list for a document nobody has annotated, and refuses an empty submit (test/annotations.test.ts)；serves an empty list with the submit statement for a document nobody has annotated (test/server.test.ts)；shows an empty state and keeps the submit entry (web/test/annotationList.test.tsx) | pass |
| spec-00007-AC-9.10 | reads a question with no thread payload yet as running (web/test/annotationMapping.test.tsx) | pass |
| spec-00007-AC-9.11 | reads failed, and follows the thread back to running after a resend from the question list (web/test/annotationBoard.test.tsx)；mirrors the thread’s last exchange, resend included (web/test/annotationMapping.test.tsx) | pass |
| spec-00007-AC-9.12 | locates in the view state the reader was last on (web/test/annotationBoard.test.tsx)；marks the rendered passage in the preview (web/test/annotationEditor.test.tsx) | pass |
| spec-00007-AC-9.13 | lets the reading go when the annotations cannot be read (web/test/annotationBoard.test.tsx)；marks the passage in the editor and in the preview (web/test/annotationEditor.test.tsx)；draws a trace over the interval it is given (web/test/annotationMapping.test.tsx) | pass |
| spec-00007-AC-9.14 | shows a stopped question as terminated (web/test/annotationList.test.tsx)；mirrors the thread’s last exchange, resend included (web/test/annotationMapping.test.tsx) | pass |
| spec-00007-AC-10.1 | holds back the issues while a session of its own runs, and takes them after it ends (test/annotations.test.ts) | pass |
| spec-00007-AC-10.2 | hands the annotations back on a stop and on a failure, keeping the row (test/annotationStore.test.ts)；holds back the issues while a session of its own runs, and takes them after it ends (test/annotations.test.ts)；summarises a partial submit and leaves each reason on its row (web/test/annotationBoard.test.tsx) | pass |
| spec-00007-AC-10.3 | holds back the issues on a full cap without transitioning or starting anything (test/annotations.test.ts) | pass |
| spec-00007-AC-10.4 | refuses a second submit of the same document while one is in flight (test/annotations.test.ts)；refuses a change and a delete of its annotations, and says which refusal it is (test/annotations.test.ts)；is out while a submit is on its way (web/test/annotationList.test.tsx) | pass |
| spec-00007-AC-10.5 | refuses a question when no agent declares a headless form, and takes an issue (test/annotations.test.ts)；withholds the question type when no agent declares a headless form (test/annotations.test.ts)；holds back every question when nothing can answer one, and starts the cowrite (test/annotations.test.ts)；draws no question entry with no headless agent (web/test/annotate.test.tsx)；offers no move to question with no headless agent (web/test/annotationList.test.tsx) | pass |
| spec-00007-AC-10.6 | refuses the whole submit when the document has been renamed, keeping the annotations (test/annotations.test.ts)；answers 409 doc-missing when the document is gone, keeping the annotations (test/server.test.ts)；says a refused submit in one toast and leaves the list alone (web/test/annotationBoard.test.tsx) | pass |
| spec-00007-AC-10.7 | hands the annotations back on a stop and on a failure, keeping the row (test/annotationStore.test.ts)；records a stop as terminated and hands the annotation back (test/annotations.test.ts) | pass |
| spec-00007-AC-10.8 | writes off a batch the last process was killed with, at the next boot (test/annotationStore.test.ts)；writes off a batch the last process was killed with, at the next boot (test/server.test.ts) | pass |
| spec-00007-AC-11.1 | makes exactly the transition and the collapse commit, leaving the document on draft (test/server.test.ts) | pass |
| spec-00007-AC-11.2 | makes no commit at all for a submit of questions, thread and answer included (test/server.test.ts) | pass |
| spec-00007-AC-11.3 | keeps the annotations on disk and offers neither type (test/annotations.test.ts)；leaves the annotations of a renamed document unreachable and on disk (test/server.test.ts) | pass |
| spec-00007-AC-11.4 | keeps the annotations on disk and offers neither type (test/annotations.test.ts)；leaves the annotations of a deleted document unreachable and on disk, batch and all (test/server.test.ts) | pass |
| spec-00007-AC-12.1 | degrades one annotation’s reading and not its neighbour’s (test/annotations.test.ts)；degrades a submitted annotation’s locate without changing its state (web/test/annotationList.test.tsx)；separates an orphan from a submitted anchor that no longer lands (web/test/annotationMapping.test.tsx) | pass |
| spec-00007-AC-12.2 | leaves the state and the quote alone and reports the reading as failed (test/annotations.test.ts) | pass |
| spec-00007-AC-12.3 | degrades one annotation’s reading and not its neighbour’s (test/annotations.test.ts)；degrades a submitted annotation’s locate without changing its state (web/test/annotationList.test.tsx)；separates an orphan from a submitted anchor that no longer lands (web/test/annotationMapping.test.tsx) | pass |

无未完成或未覆盖条目：交付范围内 72 条全部有过测行。

## 本轮补测的口径与层级

- **AC-8.1…AC-8.5**（共写行为无差别）：T3/T4 期只由 `spec-00006` 的既有
  测试沿同一代码路径覆盖，本轮按「标注发起的会话」这一 Given 各补一条服务端
  集成：同文档互斥拒推进、状态锁拒接收、收束过滤复原写域外、静默过阈读作
  等待输入、会话自然结束后文档仍为 `draft`。等待输入的**徽标与离场通知**是
  与会话种类无关的既有呈现（`web/test/sessions.test.tsx`、
  `web/test/notifications.test.tsx`），故 AC-8.3 断言的是驱动二者的
  `awaiting` 标记本身。
- **AC-11.1 / AC-11.2**（commit 全程计数）：在真实 git 仓上从提交走到收束，
  逐次数 commit——含流转的 issue 提交恰两次（`wb(status)` 与 `wb(cowrite)`，
  且收束 commit 只含目标文档）、只含 question 的提交全程零次（该测试用
  配置自带的 agent 进程真跑答疑，线程确有回答后再计数）。
- **AC-3.1 / AC-3.3 / AC-9.8**（刷新与重启保留）：在同一 `.whiteboard`
  目录上另起一个 board——重启即此——横跨未提交（含改过文本的那条）、
  已提交 question（连其线程）与已完成批（连收束 commit）三态逐项复核。
  共写中的批在重启时按 AC-10.8 核销为失败，那一支是它自己的测试。
- **AC-9.11**（重发走问题列表）：由前端集成走完整条路——标注行读作失败、
  切到问题列表展开线程重发、回到标注列表读作进行中，并断言标注自身未被
  写过任何状态（行是镜像）；`web/test/annotationMapping.test.tsx` 的映射
  单测继续钉住镜像取「最后一次往复」的粒度。
- **AC-12.1**（会话改掉邻条锚文本）：共写会话真在注册表里运行、批为
  「共写中」，会话对目标文档的那次写入由测试代进程落盘——**这是模拟的
  一层**：会话进程是 pty 替身，落盘是它与外界唯一的接缝，故断言取自磁盘
  与列表读数。全链路上真实 agent 改写邻条锚文本一节，属手工冒烟（下节）。
- **design-00002 §16.2 末条**（共写只读期照常可加注）：新增一条前端集成，
  在会话持有缓冲（`contenteditable=false`）时右键仍呈两个加注项。该条无
  对应 AC 编号，故不在上表，记于此。

## 尚未由自动化承担的部分

- `plan-00023` Detailed Acceptance Path 第 2 条的**真实 CLI 手工冒烟**
  （加注 → 改删/重新选区 → 统一提交 → question 作答 + issue 共写逐条修订
  → 收束 → 进展呈现与定位）本轮未执行：上表 72 行全部来自自动化测试，
  无一行以手工核验充数。共写形态下 claude 的写域与授权交互实测同属此列
  （`record-00022` 已载该验证义务，本轮不变）。这不阻断 `resolved` 的
  条目门（72/72 有过测行），但 plan 的验收路径第 2 条须由域主在真实白板上
  走一遍后才算走完。

## 备注

- 上表逐 AC 一行，多条测试共用一格以「；」分隔；测试名取自代码，路径相对
  `tools/whiteboard/`。溯源标注在代码里为 `// spec-00007-AC-x.y`（同一行
  的续写取缩写形 `AC-x.y`，同仓内既有惯例）。
- 修正一处 T3 期遗留的注释残缺（`test/annotations.test.ts` 中
  `spec-00007-FR-11` 段的块注释被重复起头），只动注释文本。
