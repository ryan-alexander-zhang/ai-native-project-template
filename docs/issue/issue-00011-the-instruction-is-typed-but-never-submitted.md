---
id: issue-00011-the-instruction-is-typed-but-never-submitted
type: issue
status: open
blocks: [spec-00001-docs-whiteboard]
---

# Issue: 任务指令被敲进输入框却从不提交

> 会话启动后指令全文停在 CLI 的输入框里等一次没人会按的回车：用户看到一框
> 预填文本而不是开口的 agent，还会把后续输入拼进这段文本（如 `/exit` 失效）。

## 1. Problem

- Observed: 会话启动后，任务指令完整出现在 Claude Code TUI 的输入框内、
  未提交；agent 不开口。用户此时输入 `/exit` 等命令会拼接在指令文本之后
  整体发送，斜杠命令因此失效。
- Expected: 指令作为首个输入**发送并提交**，agent 随即开始（澄清=出第一题，
  答疑=回应并等提问）——spec-00001-AC-11.2「任务指令作为首个输入」的本意。
- Trigger: 任何真实 TUI 型 agent CLI 的会话；issue-00009 修复后 TUI 首次
  可读，本缺陷随即可见。

## 2. Impact

- Affected: 三种会话在真实 claude CLI 下的启动；用户不知道要按一次回车，
  且输入框里的残留文本吞掉后续命令。
- Since: MVP（指令写入方式自始如此）· Still occurring: yes
- Severity: 高——澄清/答疑的"agent 先开口"流程走不到第一步。

## 3. Root Cause (first principles)

1. 提交与换行是两个键：Claude Code 的输入框把 LF（`\n`）当作**框内换行**，
   提交需要 CR（`\r`，即 Enter 键的实际字节）。
2. 最小机制：`tools/whiteboard/src/sessionManager.ts:139`
   `session.pty.write(\`${plan.instruction}\n\`)` ——指令以 LF 结尾写入，
   多行指令的内部 LF 逐行进框（这部分行为正确），结尾那个 LF 也只是再换
   一行，永远等不来 CR。
3. 真根因是**结尾字节选错**，不是时序（文本确实进了框）、不是指令太长。

- Introduced by: MVP 的首次指令写入实现。此前无 TUI 可见（issue-00009），
  替身脚本按行读 stdin、LF 即够，所以从未暴露。

## 4. Scope (same-cause sweep)

| Site | Same pattern | Affected | Action |
| --- | --- | --- | --- |
| `tools/whiteboard/src/sessionManager.ts:139` 指令结尾 `\n` | yes | yes | 结尾改 `\r`（指令内部换行不动） |
| `/api/terminal` 的用户击键转发（`server.ts`） | no | no | xterm 的 Enter 本就发 `\r`，原样转发正确 |

## 5. Reproduction (test-first)

- Failing test: `test/sessionManager.test.ts::ends the instruction with the
  carriage return that submits it` —— 首跑失败于
  `expected ['first line\nsecond line\n'] to deeply equal
  ['first line\nsecond line\r']`；指令内部换行保持 `\n` 亦被同一断言钉住。

## 6. Fix

- Change: 指令写入的结尾字节 `\n` → `\r`。
- Why this addresses the root cause and not the symptom: 提交键本来就是
  CR，补的是那个键本身。

## 7. Verification

- §5 的回归测试通过；两处以原始字节断言的既有用例（尺寸帧不进 stdin 的
  两例）按预期把结尾 `\n` 改 `\r`，语义未变；经真 PTY 的行规程（ICRNL）
  按行读 stdin 的既有替身逐字未动仍绿。套件 660 测试全绿、覆盖率不回落。
- **待回填**：真实 claude CLI 会话"指令即发即答"的实测（域主执行），确认
  前保持 `open`。

## 8. Follow-through

- Detection gap: 同 issue-00009——替身与假 PTY 都不区分 LF/CR；新测试把
  结尾字节钉成契约。
- Doc verdict: **code was non-conformant** —— AC-11.2 的「作为首个输入
  发送」本就蕴含提交；design-00001 §7 加一句注记，spec 不动。
- Residual state: none。

## Links

- Blocks: spec-00001-docs-whiteboard
- Related: issue-00009-terminal-size-never-reaches-the-pty · issue-00012-stop-cannot-end-a-process-that-ignores-sighup
