---
id: issue-00010-a-stuck-session-locks-the-board
type: issue
status: resolved
blocks: [spec-00001-docs-whiteboard]
---

# Issue: 卡死的会话锁死整个白板，且没有任何终止入口

> 单会话约束（FR-18）设计正确，但白板没有给用户任何结束会话的手段：会话一旦
> 卡住（如 issue-00009 的 TUI 错位），推进/澄清/答疑全局禁用，唯一解法是重启
> 服务。

## 1. Problem

- Observed: 会话卡在 running，所有节点的推进、澄清、答疑入口禁用且无解释；
  终端面板的 × 只是收起面板（会话按 FR-21 在服务端存续）；用户找不到任何
  终止手段。
- Expected: 用户能从白板终止运行中的会话，终止后发起入口恢复可用；禁用的
  入口应说明原因。FR-18/FR-21 都只规定了"存续与互斥"，没有规定"如何结束"——
  这是规格缺口，不是实现违规。
- Trigger: 任何不能自行退出的会话；issue-00009 使其必然出现。

## 2. Impact

- Affected: 单用户白板的全部会话功能；触发后功能性等同服务不可用。
- Since: MVP（单会话约束落地时）· Still occurring: no（本 issue 已修）
- Severity: 高——与 issue-00009 叠加时白板被锁死；单独出现时（agent 真卡住）
  同样无解。

## 3. Root Cause (first principles)

1. 约束（同时仅一会话）有实现，约束的解除手段（结束会话）没有用户入口。
2. 最小机制：`tools/whiteboard/src/sessionManager.ts:169-171` 已有 `stop()`
   （`pty.kill()`），但它在生产代码中**没有任何调用者**（唯一调用在
   `tools/whiteboard/test/sessionManager.test.ts` 的清理里）；
   `tools/whiteboard/src/server.ts` 没有任何路由暴露它，
   `tools/whiteboard/web/src/api.ts` 没有对应调用，
   `tools/whiteboard/web/src/Terminal.tsx` 头部只有收起按钮——且面板收起后
   同页内没有任何重开入口（`web/src/useBoard.ts` 只在发起会话或整页重载时
   打开终端）；`tools/whiteboard/web/src/Toolbar.tsx` 的三个禁用按钮无
   tooltip（design-00002 只为「no next step」规定了 tooltip）。
3. 真根因是**能力缺失**（规格从未要求终止入口），不是互斥约束错了，也不是
   FR-21 的存续设计错了。

- Introduced by: MVP 会话生命周期设计（spec 第一轮）只写了启动、存续、自然
  退出三态；"用户主动终止"从未进入规格。此前不可能发生——没有会话就没有
  卡死。

## 4. Scope (same-cause sweep)

| Site | Same pattern | Affected | Action |
| --- | --- | --- | --- |
| `tools/whiteboard/src/server.ts`（无 DELETE /api/sessions） | yes | yes | 加终止路由 |
| `tools/whiteboard/web/src/Terminal.tsx` 头部（无 Stop） | yes | yes | 加 Stop 按钮（仅 running 时可用） |
| `tools/whiteboard/web/src/useBoard.ts`（面板收起后无重开入口，Stop 不可达） | yes | yes | 顶栏会话入口重开终端（AC-49.8） |
| `tools/whiteboard/web/src/Toolbar.tsx` 禁用无解释 | yes | yes | 三个发起入口禁用时加 tooltip |
| 服务停机路径（`tools/whiteboard/src/server.ts:184` 只关 watcher，不杀会话） | yes | no | 本轮不改：停机时会话进程的兜底另行观察，`sessionManager.stop()` 现无生产调用者是事实而非保障 |

## 5. Reproduction (test-first)

1. 写在现实现下失败的测试：对运行中会话请求 `DELETE /api/sessions`，断言
   进程被结束、终端呈现结束态、随后可发起新会话；现实现该路由 404，必失败。
2. 修复后通过，留作回归护栏。

- Failing test: `test/server.test.ts` 的 DELETE /api/sessions 五例（含
  `ends the running process and leaves the end state in the terminal`）——
  首跑全部失败于 express 的 HTML 404（`Unexpected token '<', "<!DOCTYPE"`），
  即路由不存在；`web/test/panels.test.tsx::stops the session on request
  while it is running` —— 首跑失败于找不到可访问名
  `Stop the agent session` 的按钮。

## 6. Fix

- Change: 新增 spec-00001-FR-49（终止会话）：`DELETE /api/sessions` 结束
  当前会话进程；既有退出收尾照常走（结束态呈现、按 FR-14 一会话一 commit
  ——会话已写入的 docs 变更照常入库，未写则无 commit、白板刷新）；无运行中
  会话时 404。前端：终端头部 Stop 按钮（running 时可用）；三个发起入口禁用
  时带「session running」tooltip。
- Why this addresses the root cause and not the symptom: 补的是缺失的能力与
  规格，不是绕开互斥。
- Alternatives rejected: 放宽单会话约束（多会话并行）——decision-00006 §5
  已预留其举证门槛，本 issue 不动它。

## 7. Verification

- AC-49.1…49.9 逐条有通过的测试（见 record-00009）：终止即结束并呈现结束态、
  按会话种类 commit（`wb(clarify): <id>`，恰一次）、终止后可再发起、无会话/
  已退出 404、重复终止 404 且不二次 commit（变异法证明断言非恒真）、Stop 仅
  running 呈现、顶栏会话入口在面板收起时可重开终端、终止保留澄清状态文件、
  禁用入口悬停/聚焦呈现「session running」且与「no next step」并存时后者
  优先（结构性保证：两分支各持文案）。
- 套件 658 测试全绿、typecheck/build 通过、覆盖率高于基线、契约测试常绿。
- 既有护栏未回归：AC-18.x（互斥）、AC-21.x（存续）、FR-16 失败路径逐字未动。

## 8. Follow-through

- Detection gap: 验收从未包含"会话不会自然退出"的场景——实测替身脚本都会
  退出；AC-49.x 即护栏。
- Doc verdict: **the doc was missing** —— 随附 spec 修订新增 FR-49 与其 AC，
  design-00001 §7 补路由、design-00002 §3 补控件。
- Residual state: none。

## Links

- Blocks: spec-00001-docs-whiteboard
- Related: issue-00009-terminal-size-never-reaches-the-pty · decision-00006-whiteboard-ask-clarify
