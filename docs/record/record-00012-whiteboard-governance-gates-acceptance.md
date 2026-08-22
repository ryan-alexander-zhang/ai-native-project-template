---
id: record-00012-whiteboard-governance-gates-acceptance
type: record
status: active
parent: plan-00012-whiteboard-governance-gates
verifies: [spec-00002-FR-1, spec-00002-FR-2, spec-00002-FR-3, spec-00002-FR-4, spec-00002-FR-5, spec-00002-FR-6, spec-00002-FR-7, spec-00002-FR-8, spec-00002-FR-9]
---

# 验收记录：促进门、归档门、关系矩阵与撞 id

对 [plan-00012-whiteboard-governance-gates](../plan/plan-00012-whiteboard-governance-gates.md)
的验收，覆盖 [spec-00002](../spec/spec-00002-whiteboard-governance.md) 前九条
FR 的全部 49 条 AC。`issue-00015`（促进门在状态通路被绕过）与 `issue-00016`
（路径键节点无法寻址）按工作流先立后修，其复现测试即本清单相应行。测试路径
相对 `tools/whiteboard/`。

## 质量门

plan-00012 收口时（`6b2de297`）：

- `npm test`：34 个文件、901 个测试全部通过。
- `npm run typecheck`：`tsc --noEmit` 无错。
- `npm run test:coverage`：statements 99.21% / branches 95.13% /
  functions 98.73% / lines 99.73%，四项均高于 90% 门槛，未调整任何阈值。

plan-00013 落地后本清单在当前树上复核：36 个文件、947 个测试全部通过，
typecheck 无错，statements 99.14% / branches 95.39% / functions 98.65% /
lines 99.62%，阈值仍是 90% 未动。

## 验收清单

| 被验 id | 测试 | 结果 |
| --- | --- | --- |
| spec-00002-AC-1.1 | refuses to promote a draft with open questions on the status path (test/docService.test.ts) | pass |
| spec-00002-AC-1.2 | refuses to promote a draft work item with open questions into open (test/docService.test.ts) | pass |
| spec-00002-AC-1.3 | names the unresolved open questions in the refusal (test/docService.test.ts) | pass |
| spec-00002-AC-1.4 | refuses the same promotion again, still writing nothing (test/docService.test.ts) | pass |
| spec-00002-AC-1.5 | promotes a draft that has no open questions section at all (test/docService.test.ts) | pass |
| spec-00002-AC-1.6 | promotes a draft whose open questions section holds no list item (test/docService.test.ts) | pass |
| spec-00002-AC-1.7 | refuses on the status path what the accept path already refused (test/docService.test.ts) | pass |
| spec-00002-AC-2.1 | lets a draft work item reach wontfix (test/docService.test.ts) | pass |
| spec-00002-AC-2.2 | lets an active living doc go back to draft (test/docService.test.ts) | pass |
| spec-00002-AC-2.3 | lets a draft reach archived when another document supersedes it (test/docService.test.ts) | pass |
| spec-00002-AC-2.4 | lets an open plan whose scope is verified reach resolved (test/docService.test.ts) | pass |
| spec-00002-AC-2.5 | leaves an already active document active when questions appear under it (test/docService.test.ts) | pass |
| spec-00002-AC-3.1 | refuses to archive a document nothing supersedes, leaving the file alone (test/docService.test.ts) | pass |
| spec-00002-AC-3.2 | names the missing supersedes pairing in the refusal (test/docService.test.ts) | pass |
| spec-00002-AC-3.3 | refuses to archive a resolved plan nothing supersedes (test/docService.test.ts) | pass |
| spec-00002-AC-3.4 | refuses the same archive again, still writing nothing (test/docService.test.ts) | pass |
| spec-00002-AC-4.1 | archives when the superseding document is itself a draft (test/docService.test.ts) | pass |
| spec-00002-AC-4.2 | archives when the superseding document is of another type (test/docService.test.ts) | pass |
| spec-00002-AC-4.3 | refuses to archive a document that only supersedes itself (test/docService.test.ts) | pass |
| spec-00002-AC-4.4 | archives when two documents both supersede it (test/docService.test.ts) | pass |
| spec-00002-AC-4.5 | archives when the superseding document also replaces two others (test/docService.test.ts) | pass |
| spec-00002-AC-4.6 | archives when the superseding document is itself an anomalous node (test/docService.test.ts) | pass |
| spec-00002-AC-5.1 | passes a field its type is allowed to carry (test/diagnostics.test.ts) | pass |
| spec-00002-AC-5.2 | reports a field its type does not carry, naming the field, the type and the document (test/diagnostics.test.ts) | pass |
| spec-00002-AC-5.3 | reports any relation field on a type whose allowed set is empty (test/diagnostics.test.ts) | pass |
| spec-00002-AC-5.4 | checks nothing about a type the matrix does not list (test/diagnostics.test.ts) | pass |
| spec-00002-AC-6.1 | rejects a matrix entry for a type the config does not declare, naming it (test/config.test.ts) | pass |
| spec-00002-AC-6.2 | rejects a relation field the config does not declare, naming it (test/config.test.ts) | pass |
| spec-00002-AC-6.3 | rejects a matrix value that is not a list of strings, naming the type (test/config.test.ts) | pass |
| spec-00002-AC-6.4 | reads a missing matrix as no matrix at all, and still starts (test/config.test.ts)；reports nothing at all when the flow config carries no matrix (test/diagnostics.test.ts) | pass |
| spec-00002-AC-7.1 | reports a field its type does not carry, naming the field, the type and the document (test/diagnostics.test.ts) | pass |
| spec-00002-AC-7.2 | leaves the node sound, its edges drawn and the anomaly list empty (test/diagnostics.test.ts) | pass |
| spec-00002-AC-7.3 | reports a parent declared with two ids, naming parent as single-valued (test/diagnostics.test.ts) | pass |
| spec-00002-AC-7.4 | counts as one diagnostic in a tree that otherwise has none (test/diagnostics.test.ts) | pass |
| spec-00002-AC-8.1 | presents both, each keyed by its own file path and carrying the colliding id (test/docRepository.test.ts)；shows the file path and the colliding id of a duplicated document (web/test/board.test.tsx) | pass |
| spec-00002-AC-8.2 | marks both anomalous, each problem naming the other file (test/docRepository.test.ts) | pass |
| spec-00002-AC-8.3 | presents all three when three documents collide, every one of them anomalous (test/docRepository.test.ts) | pass |
| spec-00002-AC-8.4 | matches every file declaring the colliding id (web/test/canvas.test.tsx) | pass |
| spec-00002-AC-8.5 | matches only the one file a path fragment names (web/test/canvas.test.tsx) | pass |
| spec-00002-AC-8.6 | breaks an edge aimed at the colliding id (test/docRepository.test.ts) | pass |
| spec-00002-AC-8.7 | leaves a document colliding on its id out of the payload (test/docService.test.ts)；lets no requirement item of a colliding document be claimed (test/docRepository.test.ts) | pass |
| spec-00002-AC-8.8 | refuses to resolve a plan whose scope names an item of a colliding document (test/docService.test.ts) | pass |
| spec-00002-AC-8.9 | keeps the selection on the same file when the node is keyed by its path (web/test/refresh.test.tsx) | pass |
| spec-00002-AC-8.10 | clears the anomaly on the survivor once the other file is gone (test/docRepository.test.ts) | pass |
| spec-00002-AC-8.11 | clears the anomaly on both once one of them takes a free id (test/docRepository.test.ts) | pass |
| spec-00002-AC-9.1 | offers a colliding document the same editor-only toolbar (web/test/toolbar.test.tsx) | pass |
| spec-00002-AC-9.2 | refuses it, names the files to fix, and writes nothing (test/docService.test.ts)；answers 409 for an id two documents declare, naming the files to fix (test/server.test.ts) | pass |
| spec-00002-AC-9.3 | refuses the same request again, still writing nothing (test/docService.test.ts) | pass |
| spec-00002-AC-9.4 | saves an edit addressed by the node path, writing that one file only (test/docService.test.ts) | pass |

交付范围内没有未覆盖或未通过的条目。

## 实现期的既定取舍

- `issue-00015`（促进门）与 `issue-00016`（未编码的节点键）都按 test-first
  复现：失败输出记回各自 issue 的正文，修复后其回归测试转正，两份 issue
  已置 `resolved`。
- `spec-00002-AC-8.7` 在 plan-00012 收口时只在 docRepository 层验证（撞 id
  文档的条目不被认领）；`GET /api/coverage` 由 plan-00013 交付后，才由
  docService `coverage` 的「leaves a document colliding on its id out of
  the payload」把「不在列」这一面直接验证，本行的两条测试即此。
