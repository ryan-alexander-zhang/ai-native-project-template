---
id: record-00010-whiteboard-audit-and-resolved-gate-acceptance
type: record
status: active
parent: plan-00010-whiteboard-audit-and-resolved-gate
verifies: [spec-00001-FR-50, spec-00001-FR-51, spec-00001-FR-52, spec-00001-AC-18.3, rule-00001-BR-16, rule-00001-BR-23, rule-00001-BR-24, rule-00001-BR-25]
---

# 验收记录：审计评审动作与 plan 的 resolved 门

对 [plan-00010-whiteboard-audit-and-resolved-gate](../plan/plan-00010-whiteboard-audit-and-resolved-gate.md)
的验收。取舍见
[decision-00007](../decision/decision-00007-whiteboard-audit-and-resolved-gate.md)。
`rule-00001-BR-22` 是 agent 行为规则，不入本 plan 交付范围；其可断言面由
AC-50.2 的指令契约行承载。本 record 亦是 resolved 门的第一份真实证据——
plan-00010 的收口经白板的门放行完成。

## 质量门

- `npm test`：31 个文件、736 个测试全部通过（后端 407 + 前端 329）。
- `npm run typecheck`：`tsc --noEmit` 无错。
- `npm run test:coverage`：statements 99.33% / branches 95.39% /
  functions 98.89% / lines 99.76%，四项均高于 90% 门槛，未调整任何阈值。

## 验收清单

| 被验 id | 测试 | 结果 |
| --- | --- | --- |
| spec-00001-AC-50.1 | starts an audit session on a draft spec and streams its output to the terminal (test/server.test.ts) | pass |
| spec-00001-AC-50.2 | names the session kind, the document, and the folder README it is held to；states where findings land, that duplicates are not re-appended, and that status never moves (test/sessionTasks.test.ts)；plans an audit session carrying the document and its folder README (test/docService.test.ts) | pass |
| spec-00001-AC-50.3 | commits what an audit session wrote under docs, naming the action and the document (test/server.test.ts) | pass |
| spec-00001-AC-50.4 | leaves the document and the history alone when an audit session wrote nothing (test/server.test.ts) | pass |
| spec-00001-AC-51.1 | answers 422 and starts nothing for a draft of a type that is not auditable (test/server.test.ts)；refuses a draft of a type that is not auditable (test/docService.test.ts)；rejects a draft of a type that is not auditable (test/workflow.test.ts) | pass |
| spec-00001-AC-51.2 | answers 422 and starts nothing for an auditable type that is no longer draft (test/server.test.ts)；refuses a document that is not draft (test/docService.test.ts)；rejects an auditable type that is no longer a draft (test/workflow.test.ts)；leaves audit out for a spec that is no longer a draft (web/test/toolbar.test.tsx) | pass |
| spec-00001-AC-51.3 | answers 422 and starts nothing for an anomalous document (test/server.test.ts)；refuses an anomalous document (test/docService.test.ts)；rejects an anomalous document (test/workflow.test.ts) | pass |
| spec-00001-AC-52.1 | lets a plan through when the records naming it verify its whole scope (test/docService.test.ts)；applies the transition once the record naming the plan verifies its scope (test/server.test.ts) | pass |
| spec-00001-AC-52.2 | refuses, names the item, and writes nothing when a criterion has no row (test/docService.test.ts)；answers 422 naming the gaps, and leaves the file alone (test/server.test.ts) | pass |
| spec-00001-AC-52.3 | refuses when a row of the scope exists but did not pass (test/docService.test.ts) | pass |
| spec-00001-AC-52.4 | refuses when the passing rows belong to a record naming another plan (test/docService.test.ts) | pass |
| spec-00001-AC-52.5 | refuses and names an id its scope could not resolve (test/docService.test.ts) | pass |
| spec-00001-AC-52.6 | lets a plan whose scope is empty through with no evidence at all (test/docService.test.ts) | pass |
| spec-00001-AC-52.7 | refuses and names the one item of a whole document in scope that nothing verifies (test/docService.test.ts)；names the one item of a whole document in scope that nothing verifies (test/resolvedGate.test.ts) | pass |
| spec-00001-AC-52.8 | lets an issue reach resolved without consulting the gate (test/docService.test.ts) | pass |
| spec-00001-AC-52.9 | lets a plan with an unverified scope reach wontfix (test/docService.test.ts) | pass |
| spec-00001-AC-52.10 | lets a plan through on coverage spread across two records naming it (test/docService.test.ts) | pass |
| spec-00001-AC-18.3 | answers 409 for an audit while a clarify session is running, leaving it alone；answers 409 for a clarify, an ask and an advance while an audit session is running (test/server.test.ts) | pass |
| rule-00001-AC-16.1 | offers task, issue, and record for a plan, each carrying its own relation (test/workflow.test.ts)；loads and matches rule-00001 product flow (test/config.test.ts) | pass |
| rule-00001-AC-16.2 | advances a plan into a record carrying parent and an issue carrying blocks — record leg (test/acceptance.test.ts)；offers task, issue, and record for a plan, each carrying its own relation (test/workflow.test.ts) | pass |
| rule-00001-AC-16.3 | advances a plan into a record carrying parent and an issue carrying blocks — issue leg (test/acceptance.test.ts)；offers task, issue, and record for a plan, each carrying its own relation (test/workflow.test.ts) | pass |
| rule-00001-AC-23.1 | allows a draft of every auditable type (test/workflow.test.ts)；plans an audit session carrying the document and its folder README (test/docService.test.ts) | pass |
| rule-00001-AC-23.2 | rejects a draft of a type that is not auditable (test/workflow.test.ts)；refuses a draft of a type that is not auditable (test/docService.test.ts) | pass |
| rule-00001-AC-23.3 | rejects an auditable type that is no longer a draft (test/workflow.test.ts)；refuses a document that is not draft (test/docService.test.ts) | pass |
| rule-00001-AC-24.1 | takes an item id itself and passes over a design target (test/resolvedGate.test.ts) | pass |
| rule-00001-AC-24.2 | takes every item of a whole rule document (test/resolvedGate.test.ts) | pass |
| rule-00001-AC-24.3 | is empty for a plan that implements only a design document (test/resolvedGate.test.ts) | pass |
| rule-00001-AC-24.4 | takes the owning item of an acceptance criterion (test/resolvedGate.test.ts) | pass |
| rule-00001-AC-25.1 | finds no gap when every criterion of every scope item has a passing row (test/resolvedGate.test.ts)；lets a plan through when the records naming it verify its whole scope (test/docService.test.ts) | pass |
| rule-00001-AC-25.2 | names the item when one of its criteria has no row at all (test/resolvedGate.test.ts)；refuses, names the item, and writes nothing when a criterion has no row (test/docService.test.ts) | pass |
| rule-00001-AC-25.3 | names the item when a row of one of its criteria did not pass (test/resolvedGate.test.ts)；refuses when a row of the scope exists but did not pass (test/docService.test.ts) | pass |
| rule-00001-AC-25.4 | refuses when the passing rows belong to a record naming another plan (test/docService.test.ts) | pass |
| rule-00001-AC-25.5 | reports a target that is neither a document nor an item；names an unresolvable target as a gap of its own (test/resolvedGate.test.ts)；refuses and names an id its scope could not resolve (test/docService.test.ts) | pass |
| rule-00001-AC-25.6 | finds no gap when the scope is empty, whatever the records say (test/resolvedGate.test.ts)；lets a plan whose scope is empty through with no evidence at all (test/docService.test.ts) | pass |
| rule-00001-AC-25.7 | takes the coverage of two records together (test/resolvedGate.test.ts)；lets a plan through on coverage spread across two records naming it (test/docService.test.ts) | pass |

## 实现期的既定取舍

- 审计指令不携带关系文档路径——FR-50 只枚举目标路径与文件夹 README，与
  澄清/答疑的 relatedPaths 是有意的差别。
- 「范围为空但含无法解析 id」拒绝流转：BR-25 的「无法解析视为缺口」独立于
  「范围为空不受约束」，取更严的读法（AC-52.5 的口径）。
- 可审计类型集在前后端各持一份代码内建副本（FR-50 的「代码内建」无配置
  通道），后续如要单一来源可走 `GET /api/config`。
- 门拒绝的 toast 文案随既有 UI 用英文（`N items unverified: …`），超过
  5 个 id 截断并保留计数。
