---
id: record-00015-advance-inherits-open-questions-acceptance
type: record
status: active
parent: plan-00015-advance-inherits-open-questions
verifies: [spec-00001-FR-11]
---

# 验收记录：推进指令的来源路径与上游未决点继承

对 [plan-00015-advance-inherits-open-questions](../plan/plan-00015-advance-inherits-open-questions.md)
的验收。本轮交付的是 `spec-00001-FR-11` 第十三轮新增的来源文档路径（无条件）
与上游未决点继承段（条件，目标类型属可澄清集），清单按条目口径列全 FR-11 的
四条 AC：`AC-11.1`/`AC-11.2` 引既有测试，`AC-11.3`/`AC-11.4` 引本轮新增。
测试路径相对 `tools/whiteboard/`。

## 质量门

- `npm test`：37 个文件、952 个测试全部通过。
- `npm run typecheck`：`tsc --noEmit` 无错。
- `npm run test:coverage`：statements 99.14% / branches 95.4% /
  functions 98.66% / lines 99.62%，四项均高于 90% 门槛，未调整任何阈值。

## 验收清单

| 被验 id | 测试 | 结果 |
| --- | --- | --- |
| spec-00001-AC-11.1 | starts an advance the flow config allows (test/server.test.ts)；runs the configured command as the session (test/sessionManager.test.ts) | pass |
| spec-00001-AC-11.2 | names the target type, the fixed id number, and the relation to the source (test/advance.test.ts) | pass |
| spec-00001-AC-11.3 | gives the source path and asks a clarifiable target to inherit the unresolved questions (test/advance.test.ts) | pass |
| spec-00001-AC-11.4 | leaves the inheritance out for a target with no Open Questions, and still gives the source path (test/advance.test.ts) | pass |

交付范围内没有未覆盖或未通过的条目。

## 实现期的既定取舍

- 来源路径以「相对 docs 树」的写法给出，与 clarify/ask/audit 三类指令的
  `docPath` 同一相对性——会话的工作目录就是 docs 树。
- `taskInstruction` 增第二个参数 `sourcePath`，而不是把路径塞进 `Expectation`：
  `Expectation` 是产出校验的尺子（`{targetType, 编号, carry, sourceId}`，
  design-00001 §4），来源路径不属于对产出的期望。路径由 `DocService.pathOf`
  从图节点取，调用点即 `Board.startSession`。
- 继承段的可澄清判定复用 `clarifyRules.ts` 的 `isClarifiable`，不复制该集合。
