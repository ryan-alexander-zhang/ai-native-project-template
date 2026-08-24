---
id: record-00020-whiteboard-explicit-waiting-signal-acceptance
type: record
status: active
parent: plan-00020-whiteboard-explicit-waiting-signal
verifies: [spec-00003-whiteboard-parallel-sessions]
---

# 验收记录：等待输入的显式信号通路（OSC 777 锁存）

对 [plan-00020-whiteboard-explicit-waiting-signal](../plan/plan-00020-whiteboard-explicit-waiting-signal.md)
的验收。本轮交付 `spec-00003` 第十八轮增量：FR-6 双通路 + 锁存的信号
通路半边——新增 `spec-00003-AC-6.6` … `AC-6.13` 共 8 条各落一测，既有
`AC-6.1` … `AC-6.5`（静默通路）回归通过（AC-6.2 的 Given 本轮收紧为
「经静默通路置位、未锁存」，其既有测试语义未变、无需改动）。纯服务端
`sessionManager` 改动，零页面改动（plan T3 范围）；design-00001 修订轮
（T1）与 spec-00004 括注校正轮（T4）先行完成。测试路径相对
`tools/whiteboard/`。

## 质量门

- `npm test`：41 个文件、1119 个测试全部通过（1111 基线 + 本轮 8 条）。
- `npm run typecheck`：`tsc --noEmit` 无错。
- `npm run test:coverage`：statements 99.31% / branches 95.67% /
  functions 98.68% / lines 99.68%，四项均高于 90% 门槛且不低于改动前
  （99.31/95.63/98.68/99.67），未调整任何阈值。

## 验收清单

| 被验 id | 测试 | 结果 |
| --- | --- | --- |
| spec-00003-AC-6.1 | 既有回归（test/sessionManager.test.ts 管理端半边 + web/test 面板半边） | pass |
| spec-00003-AC-6.2 | 既有回归（同上；Given 收紧后语义未变） | pass |
| spec-00003-AC-6.3 | 既有回归（同上） | pass |
| spec-00003-AC-6.4 | 既有回归（test/sessionManager.test.ts） | pass |
| spec-00003-AC-6.5 | 既有回归（web/test 面板与徽标计数 + test/server.test.ts 服务端半边） | pass |
| spec-00003-AC-6.6 | marks a session that says it is waiting, mid-output and without the threshold (test/sessionManager.test.ts) | pass |
| spec-00003-AC-6.7 | keeps the mark up through the redraws that follow the signal (test/sessionManager.test.ts) | pass |
| spec-00003-AC-6.8 | unlatches on a single keystroke and arms the silence path again (test/sessionManager.test.ts) | pass |
| spec-00003-AC-6.9 | drops a latched mark when the session ends (test/sessionManager.test.ts) | pass |
| spec-00003-AC-6.10 | never marks a signal in the trailing output of a process that has exited (test/sessionManager.test.ts) | pass |
| spec-00003-AC-6.11 | turns a silence-set mark into a latched one without a flap (test/sessionManager.test.ts) | pass |
| spec-00003-AC-6.12 | ignores a repeated signal while latched (test/sessionManager.test.ts) | pass |
| spec-00003-AC-6.13 | recognises a signal split across two chunks (test/sessionManager.test.ts) | pass |
