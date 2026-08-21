---
id: record-00011-whiteboard-revision-create-and-session-reach-acceptance
type: record
status: active
parent: plan-00011-whiteboard-revision-create-and-session-reach
verifies: [spec-00001-FR-53, spec-00001-FR-54, spec-00001-FR-55, spec-00001-FR-56, spec-00001-AC-6.5, spec-00001-AC-17.3, rule-00001-BR-3, rule-00001-BR-26, rule-00001-BR-27]
---

# 验收记录：修订轮、新建入口与会话历史

对 [plan-00011-whiteboard-revision-create-and-session-reach](../plan/plan-00011-whiteboard-revision-create-and-session-reach.md)
的验收。取舍见
[decision-00008](../decision/decision-00008-whiteboard-revision-create-and-session-reach.md)。
`spec-00001-AC-6.5` 与 `spec-00001-AC-17.3` 属交付范围外条目（FR-6、FR-17）
但由本轮修订产生，一并在此验收；`issue-00014`（lastFinding 陈旧）按工作流
先立后修，其回归测试即 AC-17.3 的行。图缓存为 spec §7 非功能项，无 GWT，
其行为测试见 docService 的 parse cache 用例。测试路径相对
`tools/whiteboard/`。

## 质量门

- `npm test`：34 个文件、832 个测试全部通过（后端 460 + 前端 372）。
- `npm run typecheck`：`tsc --noEmit` 无错。
- `npm run test:coverage`：statements 99.18% / branches 94.97% /
  functions 98.68% / lines 99.72%，均高于 90% 门槛，未调整任何阈值。

## 验收清单

| 被验 id | 测试 | 结果 |
| --- | --- | --- |
| rule-00001-AC-3.1 | offers draft and archived from an active living doc (test/statusRules.test.ts)；offers draft and archived but not resolved or open for an active living doc (test/workflow.test.ts)；takes an active living doc back to draft and commits it (test/docService.test.ts) | pass |
| rule-00001-AC-3.2 | lets an audit start on the re-drafted document (test/docService.test.ts) | pass |
| rule-00001-AC-3.3 | lets a clarify start on the re-drafted document (test/docService.test.ts) | pass |
| rule-00001-AC-3.4 | refuses to accept it while the revision leaves open questions (test/docService.test.ts) | pass |
| rule-00001-AC-3.5 | returns it to active on accept (test/docService.test.ts) | pass |
| rule-00001-AC-26.1 | allocates the next number and hands back the type template；creates the file at the allocated id and commits it as a create (test/docService.test.ts)；creates the document at the allocated id, as a draft, and commits it (test/server.test.ts) | pass |
| rule-00001-AC-27.1 | refuses a type that is not a flow entry, writing nothing (test/docService.test.ts)；answers 422 for a create of a type outside the entry list (test/server.test.ts) | pass |
| spec-00001-AC-6.5 | offers draft as a transition of an active living doc (test/server.test.ts)；offers draft and archived but not resolved or open for an active living doc (test/workflow.test.ts) | pass |
| spec-00001-AC-17.3 | drops the mark on the next refresh once the relation is there (test/server.test.ts)（issue-00014 的回归测试） | pass |
| spec-00001-AC-53.1 | serves the allocated id prefix and the type template without writing anything；creates the document at the allocated id, as a draft, and commits it (test/server.test.ts)；allocates the next number and hands back the type template；creates the file at the allocated id and commits it as a create (test/docService.test.ts) | pass |
| spec-00001-AC-53.2 | answers 422 for a create of a type outside the entry list；answers 422 for a type that is not a flow entry, and for no type at all (test/server.test.ts)；refuses a type that is not a flow entry, writing nothing；refuses to prefill a type that is not a flow entry (test/docService.test.ts) | pass |
| spec-00001-AC-53.3 | answers 409 for an id that already exists, without overwriting it (test/server.test.ts)；refuses an id a document already holds, leaving the disk alone；refuses an id whose file is there under a name the graph does not know (test/docService.test.ts) | pass |
| spec-00001-AC-53.4 | answers 422 for a slug with an upper-case letter or a space (test/server.test.ts)；refuses a slug that is not lower case and hyphenated (test/docService.test.ts)；refuses a slug that is not lowercase and hyphenated；does not act on Enter while the slug is not a slug (web/test/create.test.tsx) | pass |
| spec-00001-AC-53.5 | rejects an entry type the config does not declare, naming it (test/config.test.ts) | pass |
| spec-00001-AC-53.6 | reads a missing entry list as no entry types at all；reads an empty entry list the same way (test/config.test.ts)；is not drawn when the config declares no entry type；is drawn once the config declares one (web/test/create.test.tsx) | pass |
| spec-00001-AC-53.7 | commits a draft even with a pre-commit hook that rejects drafts (test/server.test.ts) | pass |
| spec-00001-AC-54.1 | lists a session that has ended with its kind, document and exit status (test/server.test.ts) | pass |
| spec-00001-AC-54.2 | serves the list and the whole transcript to a board started after a restart (test/server.test.ts) | pass |
| spec-00001-AC-54.3 | commits and refreshes all the same when the history cannot be written (test/server.test.ts) | pass |
| spec-00001-AC-54.4 | lists a stopped session with the exit status it really had (test/server.test.ts) | pass |
| spec-00001-AC-55.1 | starts an ask session on the agent the request names (test/server.test.ts) | pass |
| spec-00001-AC-55.2 | starts an advance on the first configured agent when none is named (test/server.test.ts) | pass |
| spec-00001-AC-55.3 | answers 422 and starts nothing for an agent the config does not declare (test/server.test.ts) | pass |
| spec-00001-AC-55.4 | is not drawn when the config declares one agent；names no agent at all when there is only one (web/test/agents.test.tsx) | pass |
| spec-00001-AC-56.1 | serves the built-in clarifiable and auditable type sets (test/server.test.ts) | pass |
| spec-00001-AC-56.2 | draws no audit entry on a draft design when the auditable set omits design；draws it on the same node once the set carries design (web/test/agents.test.tsx)；leaves audit out for a type that cannot be audited (web/test/toolbar.test.tsx) | pass |

## 实现期的既定取舍

- `POST /api/docs` 的服务端校验取最小面：正文 front matter 的 id 必须与请求
  id 一致；type/status 的预填归前端（FR-53 的分工）。
- spawn 失败（`failed`）的会话不写历史——FR-54 的措辞是「自然退出或终止」，
  且 FR-16 裁定此类会话不留任何产物。
- 会话 id 携带启动时间戳（历史文件以其命名，重启后的计数器不会覆盖旧历史）。
- 完整转写在内存单独累积（1 MB 回放缓冲照旧），会话越长占用越大——本地
  单会话工具可接受。
- 类型缺 `TEMPLATE.md` 时预填为空串而非拒绝新建。
- 新建后关闭编辑面板并选中新节点，不重定位视口（design §3 未要求）。
