---
id: record-00009-whiteboard-terminal-fixes-acceptance
type: record
status: active
verifies: [spec-00001-AC-12.5, spec-00001-AC-12.6, spec-00001-AC-12.7, spec-00001-FR-49]
---

# 验收记录：终端尺寸同步与会话终止

对 [issue-00009](../issue/issue-00009-terminal-size-never-reaches-the-pty.md)
（终端尺寸从不到达 PTY）与
[issue-00010](../issue/issue-00010-a-stuck-session-locks-the-board.md)
（卡死会话锁死白板）修复的验收；承载 FR-12 修订（AC-12.5…12.7）与新增
FR-49（AC-49.1…49.9）的验收行。本轮无 plan——bugfix 尺寸，issue 即载体。

- 套件：`cd tools/whiteboard && npm test` → **30 个测试文件、658 个测试全部
  通过**（record-00008 验收时为 624，净增 34）
- 覆盖率：语句 99.28%、分支 95.48%、函数 98.81%、行 99.74%（基线
  99.18/95.28/98.57/99.64，四项全升）
- 类型检查与构建通过；契约测试（真实 `docs/` 零诊断）常绿
- 测试先行：两个 issue §5 记录了首跑失败证据；首轮已正确的行为（AC-49.6/
  49.7/49.9、tooltip 优先级）以**变异法**证明断言非恒真（弱化守卫/误删状态
  文件/调换文案 → 对应测试立即变红）

测试名为 `tools/whiteboard/` 下的用例标题：`t/` = `test/`，`w/` = `web/test/`。

## 验收清单

| GWT id | 测试 | 结果 |
| --- | --- | --- |
| spec-00001-AC-12.5 | resizes the session pty so the CLI sees the terminal size (t/sessionManager，真 node-pty、子进程自报尺寸)；resizes the session pty to the size the attached terminal reports (t/server)；sends the size the terminal fitted to as soon as it attaches (w/panels) | pass |
| spec-00001-AC-12.6 | resizes the pty again for every later size frame (t/server)；fits again and sends the new size when the panel changes size (w/panels) | pass |
| spec-00001-AC-12.7 | sends no size while the panel is collapsed to nothing (w/panels)；sends no size while the terminal cannot be measured at all (w/panels) | pass |
| spec-00001-AC-49.1 | ends the running process and leaves the end state in the terminal (t/server)；stops the session on request while it is running (w/panels) | pass |
| spec-00001-AC-49.2 | commits what the stopped session wrote, named by its kind (t/server)——`wb(clarify): <id>`，只含该文档 | pass |
| spec-00001-AC-49.3 | lets a new session start once the stuck one has been stopped (t/server)；stops the session from the terminal panel and hands the entries back (w/canvas) | pass |
| spec-00001-AC-49.4 | answers 404 when no session is running (t/server)；answers 404 for a session that has already exited (t/server) | pass |
| spec-00001-AC-49.5 | says why each of Clarify, Ask, and Advance is disabled while a session runs（it.each 三入口，w/toolbar）；prefers no next step over session running when both hold (w/toolbar) | pass |
| spec-00001-AC-49.6 | refuses a second stop of the same session and commits nothing again (t/server) | pass |
| spec-00001-AC-49.7 | offers no stop for a session that has already ended (w/panels)；offers no stop when there is no session at all (w/panels) | pass |
| spec-00001-AC-49.8 | offers the session in the top bar once the terminal panel is put away (w/canvas)——收起后呈现、点击重开、重开后消失；offers no top-bar session entry when no session is running (w/canvas) | pass |
| spec-00001-AC-49.9 | leaves the clarify state file in place when the session is stopped (t/server)——文件与内容俱在 | pass |

## 协议与实现要点（非 AC 的护栏测试）

- 帧编码按 design-00001 §7：文本帧=stdin 原样、二进制帧=JSON 尺寸控制；
  护栏测试：keeps a size frame out of stdin, and a keystroke out of the
  size；drops a control frame it cannot read as a size, and carries on；
  ignores a resize with no session behind it / after the session has exited
  （均 t/server 或 t/sessionManager）。
- 退化尺寸的双重防线：前端 `proposeDimensions()` 为 0/不可测时不发（真
  `fit()` 对收起面板是静默 no-op，读 `xterm.cols` 会误发旧值——实现取舍），
  服务端正整数校验兜底（node-pty 的 `resize` 对 0 会抛异常，防线是承重的）。
- `convertEol: true` 移除：pty 行规程已做 ONLCR，它是第二重 CR 注入，破坏
  全屏 TUI 行中 LF 语义（issue-00009 §7）。
- 终止与自然退出的竞态：`pty.onExit` 只触发一次、`kill()` 吞吷已死进程的
  错误，「收尾恰一次」由此成立（design-00001 §5 注记）。

## 观察项（不阻塞）

1. **真实 claude CLI 的 TUI 渲染实测待域主回填**（issue-00009 §7）——自动
   测试证明尺寸到达了会话进程，渲染质量只能真跑确认；确认前 issue-00009
   保持 `open`。
2. `DELETE /api/sessions` 等待退出收尾后返回最终 `SessionInfo`（不是裸
   200）；`pty.kill()` 只发 SIGHUP，不升级 SIGKILL——无视信号的进程会挂住
   该请求，留待真实需要时再加宽限升级。
3. 服务停机路径不杀会话进程（issue-00010 §4 观察行），本轮未动。

## 结论

12 行 GWT 全部 pass、门禁四项全升、两条防线各自独立成立；issue-00010 置
`resolved`，issue-00009 待真实 CLI 实测回填后再置。
