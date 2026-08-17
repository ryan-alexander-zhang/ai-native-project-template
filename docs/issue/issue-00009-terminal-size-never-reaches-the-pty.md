---
id: issue-00009-terminal-size-never-reaches-the-pty
type: issue
status: open
blocks: [spec-00001-docs-whiteboard]
---

# Issue: 终端尺寸从不到达 PTY，全屏 TUI 错位到无法交互

> 内嵌终端的实际行列与会话 PTY 的行列是两个从不通气的数：全屏 TUI（claude 的
> 交互界面）按错误尺寸绘制，光标与回显错位，澄清会话一启动就"看起来卡死"。

## 1. Problem

- Observed: 澄清会话启动后，内嵌终端只见一行错位的状态栏碎片（如
  `Opus 5 (1M context) · ctx -ycle)…`），其余空白；输入无可见反馈，用户判定
  "根本无法编辑"。
- Expected: TUI 按面板实际尺寸完整绘制、可交互——spec-00001-FR-12（流式呈现
  并转发输入）与 Non-Functional「内嵌终端体验接近本地终端」。
- Trigger: 任何全屏 TUI 型 agent CLI 的会话；第八轮的澄清是首个必须在 TUI 里
  交互的场景，缺陷因此首次可见。

## 2. Impact

- Affected: 全部三种会话（推进/澄清/答疑）在真实 claude CLI 下的交互；纯流式
  输出场景只错行宽、勉强可读，交互场景不可用。
- Since: MVP（plan-00001 的终端通道）· Still occurring: yes
- Severity: 高——澄清功能事实不可用；叠加 issue-00010（会话无法终止）时整个
  白板的发起入口被锁死。

## 3. Root Cause (first principles)

1. 前端 xterm 的行列由 FitAddon 按面板尺寸算出；PTY 的行列固定 120×30。两者
   从不同步，TUI 应用按 PTY 声称的尺寸发绘制序列，xterm 按另一个尺寸解释。
2. 最小机制：`tools/whiteboard/src/pty.ts:28` 写死
   `cols: 120, rows: 30`；`tools/whiteboard/web/src/Terminal.tsx:36-42`
   `fit.fit()` 只调整了浏览器侧、结果不上报；
   `tools/whiteboard/src/server.ts:161-172` 的 `/api/terminal` 把每个浏览器
   消息原样写进 PTY stdin（`socket.on('message', … write)`），协议里没有
   任何控制帧；`tools/whiteboard/web/src/terminalSocket.ts:1-17` 的
   `TerminalLink` 只有 `send`/`close`，前端也没有控制帧的出口；仓库中没有
   任何 `pty.resize` 调用；面板拖动调整尺寸也没有 ResizeObserver 跟进重
   fit。
3. 真根因是**协议缺一类消息**（尺寸同步），不是 claude CLI 的问题、不是
   xterm 主题或字体问题、也不是会话真的挂了——进程活着，只是画错了。

- Introduced by: MVP 终端通道（plan-00001 落地时即如此）。此前不可能发生——
  没有终端就没有尺寸可错。

## 4. Scope (same-cause sweep)

| Site | Same pattern | Affected | Action |
| --- | --- | --- | --- |
| `tools/whiteboard/src/pty.ts:28` 固定 120×30 | yes | yes | 接受初始默认，落地 resize 通道后由首帧同步纠正 |
| `tools/whiteboard/web/src/Terminal.tsx:39` fit 结果不上报 | yes | yes | fit 后上报，且 ResizeObserver 跟进面板变化 |
| `tools/whiteboard/web/src/terminalSocket.ts:1-17` TerminalLink 无控制帧出口 | yes | yes | 补 resize 发送通道 |
| `tools/whiteboard/src/server.ts:161-172` WS 无控制帧 | yes | yes | 协议加尺寸控制帧（文本帧=stdin、二进制帧=JSON 控制，per design-00001 §7） |
| `tools/whiteboard/src/sessionManager.ts:151-153` 只有 write 无 resize | yes | yes | PTY 接口与会话层补 resize |
| `/api/events` 通道（`tools/whiteboard/src/server.ts:174-180`） | no | no | 无载荷信号，无尺寸概念 |

## 5. Reproduction (test-first)

1. 写在现实现下失败的测试：假 PTY 记录 resize 调用——终端 socket 接入并送出
   尺寸帧后，断言 PTY 收到 `resize(cols, rows)`；现实现无此通路，必失败。
2. 修复后通过，留作回归护栏。

- Failing test: `test/server.test.ts::resizes the session pty to the size the
  attached terminal reports` —— 首跑失败于
  `expected [] to deeply equal [{cols:100,rows:40}]`（resize 从未被调用）；
  `test/sessionManager.test.ts::resizes the session pty so the CLI sees the
  terminal size` —— 首跑失败于 `manager.resize is not a function`（真
  node-pty，子进程自报 `process.stdout.columns x rows`）；
  `test/server.test.ts::keeps a size frame out of stdin, and a keystroke out
  of the size` —— 首跑证实两种帧都被打进 stdin，即本 issue §3 的机制原文。

## 6. Fix

- Change: 终端 WS 协议增加一类尺寸控制帧（与任意击键字节严格可区分，如二进制
  帧承载 JSON、文本帧仍为 stdin）；前端在 `fit.fit()` 后与 ResizeObserver
  触发时发送 `{cols, rows}`；服务端经 `sessionManager.resize` 调
  `pty.resize`，进程收到标准 SIGWINCH 后自行重绘。spec 修订：FR-12 补尺寸
  同步句，AC-12.5/12.6 覆盖接入时与面板变化时两个时机。
- Why this addresses the root cause and not the symptom: 补上的是缺失的那类
  消息本身；不改 CLI、不改主题、不猜尺寸。

## 7. Verification

- §5 的回归测试全部通过；另有协议护栏（尺寸帧不进 stdin、非法控制帧丢弃不
  断连、无会话/已退出的迟到帧忽略、退化尺寸不下发——AC-12.7）与前端侧
  （fit 后即发、面板变化跟进、socket 未开时暂存补发）共十余例。
- 套件 658 测试全绿、typecheck/build 通过、覆盖率
  99.28/95.48/98.81/99.74 高于基线、契约测试常绿。
- 顺带修复：`web/src/Terminal.tsx` 的 `convertEol: true` 被确认有害移除——
  pty 行规程已做 ONLCR，它是第二重 CR 注入，恰好破坏全屏 TUI 行中 LF 的
  语义。
- **待回填**：真实 claude CLI 会话的 TUI 渲染实测（由域主重启服务后确认），
  确认前本 issue 保持 `open`。

## 8. Follow-through

- Detection gap: 既有测试用假 PTY 与桩 WebSocket，只断言字节流转发，从未
  断言尺寸；实测（record-00008）用替身脚本、非全屏 TUI，同样测不到。新增的
  AC-12.5/12.6 即护栏。
- Doc verdict: **the doc was missing** —— FR-12 未写尺寸同步，本 issue 随附
  spec 修订补上。
- Residual state: none（无落盘数据受损）。

## Links

- Blocks: spec-00001-docs-whiteboard · plan-00009-whiteboard-ask-clarify
- Related: issue-00010-a-stuck-session-locks-the-board
