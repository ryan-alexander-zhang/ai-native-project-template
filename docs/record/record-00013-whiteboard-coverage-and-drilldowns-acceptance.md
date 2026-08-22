---
id: record-00013-whiteboard-coverage-and-drilldowns-acceptance
type: record
status: active
parent: plan-00013-whiteboard-coverage-and-drilldowns
verifies: [spec-00002-FR-10, spec-00002-FR-11, spec-00002-FR-12, spec-00002-FR-13, spec-00002-FR-14, spec-00002-FR-15]
---

# 验收记录：全局覆盖率视图与异常/诊断下钻

对 [plan-00013-whiteboard-coverage-and-drilldowns](../plan/plan-00013-whiteboard-coverage-and-drilldowns.md)
的验收，覆盖 [spec-00002](../spec/spec-00002-whiteboard-governance.md) 后六条
FR 的全部 32 条 AC。撞 id 文档不入覆盖视图这一条属 FR-8（`spec-00002-AC-8.7`），
在 [record-00012](record-00012-whiteboard-governance-gates-acceptance.md) 验收，
本轮的 `GET /api/coverage` 测试是其证据之一。测试路径相对 `tools/whiteboard/`。

## 质量门

- `npm test`：36 个文件、947 个测试全部通过。
- `npm run typecheck`：`tsc --noEmit` 无错。
- `npm run test:coverage`：statements 99.14% / branches 95.39% /
  functions 98.65% / lines 99.62%，四项均高于 90% 门槛，未调整任何阈值。

## 验收清单

| 被验 id | 测试 | 结果 |
| --- | --- | --- |
| spec-00002-AC-10.1 | lists every spec and rule, each with its three counts (test/docService.test.ts)；serves a row per spec and rule, each with its counts and items (test/server.test.ts) | pass |
| spec-00002-AC-10.2 | counts the uncovered items of a document (test/docService.test.ts) | pass |
| spec-00002-AC-10.3 | lists nothing when the repo holds no spec and no rule (test/docService.test.ts)；says the repo holds no spec and no rule rather than showing an empty list (web/test/overview.test.tsx) | pass |
| spec-00002-AC-10.4 | updates its counts when a refresh arrives while it is open (web/test/overview.test.tsx)；re-derives the counts once the tree it read has been invalidated (test/docService.test.ts) | pass |
| spec-00002-AC-10.5 | opens over an editor holding an unsaved buffer, leaving it alone (web/test/overview.test.tsx) | pass |
| spec-00002-AC-10.6 | opens while the board is inside a sub-canvas (web/test/overview.test.tsx) | pass |
| spec-00002-AC-10.7 | closes on Escape (web/test/overview.test.tsx) | pass |
| spec-00002-AC-10.8 | closes on the close control (web/test/overview.test.tsx) | pass |
| spec-00002-AC-10.9 | lists an archived spec and a draft rule alike (test/docService.test.ts) | pass |
| spec-00002-AC-10.10 | lists a document whose front matter is broken but whose body parses (test/docService.test.ts) | pass |
| spec-00002-AC-11.1 | lists every item id with its coverage when it is expanded (web/test/overview.test.tsx) | pass |
| spec-00002-AC-11.2 | says a document declares no items rather than opening onto nothing (web/test/overview.test.tsx) | pass |
| spec-00002-AC-11.3 | collapses again when its row is clicked a second time (web/test/overview.test.tsx) | pass |
| spec-00002-AC-11.4 | collapses the open row when another is expanded (web/test/overview.test.tsx) | pass |
| spec-00002-AC-11.5 | stays expanded through a refresh (web/test/overview.test.tsx) | pass |
| spec-00002-AC-12.1 | closes the view, selects the document, and opens its inspector (web/test/overview.test.tsx) | pass |
| spec-00002-AC-12.2 | selects the document without an inspector while the editor holds the slot (web/test/overview.test.tsx) | pass |
| spec-00002-AC-12.3 | closes the view, selects the document, and opens its inspector (web/test/overview.test.tsx) | pass |
| spec-00002-AC-12.4 | comes back up out of a sub-canvas to the document it was told to go to (web/test/overview.test.tsx) | pass |
| spec-00002-AC-12.5 | refuses with a toast and keeps the selection when the document has gone (web/test/overview.test.tsx) | pass |
| spec-00002-AC-13.1 | lists every anomaly with its source and its problem text (web/test/drilldown.test.tsx) | pass |
| spec-00002-AC-13.2 | keeps the no-issues wording and offers no entry when the count is zero (web/test/drilldown.test.tsx) | pass |
| spec-00002-AC-13.3 | closes on Escape (web/test/drilldown.test.tsx) | pass |
| spec-00002-AC-13.4 | gives a broken relation the declaring document as its source (web/test/drilldown.test.tsx) | pass |
| spec-00002-AC-14.1 | lists every diagnostic with its source, its kind, and its detail (web/test/drilldown.test.tsx) | pass |
| spec-00002-AC-14.2 | renders nothing at all when the count is zero (web/test/drilldown.test.tsx) | pass |
| spec-00002-AC-14.3 | carries no anomaly of a document that is also an anomalous node (web/test/drilldown.test.tsx) | pass |
| spec-00002-AC-14.4 | names the relation-field kind, and leaves the line empty for it (web/test/drilldown.test.tsx) | pass |
| spec-00002-AC-15.1 | goes to the node of an anomalous document that still has an id (web/test/drilldown.test.tsx) | pass |
| spec-00002-AC-15.2 | goes to the spec a diagnostic came from (web/test/drilldown.test.tsx) | pass |
| spec-00002-AC-15.3 | goes to the path-keyed node of a file whose front matter will not parse (web/test/drilldown.test.tsx) | pass |
| spec-00002-AC-15.4 | goes to the declaring document of a broken relation (web/test/drilldown.test.tsx) | pass |

交付范围内没有未覆盖或未通过的条目。

## 实现期的既定取舍

- `spec-00002-AC-12.1` 与 `AC-12.3` 是同一次点击的两个断言面（定位选中 +
  视图关闭），由同一条测试一并断言，故两行同名。
- 覆盖率载荷的证据集取全部 record、不分 status（design-00001 §7），与
  resolved 门读同一份 `requirements` 推导，其一致性由 docService coverage
  的「gives the row the very coverage /items gives the same document」守住。
