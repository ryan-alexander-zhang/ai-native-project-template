---
id: issue-00011-the-instruction-is-typed-but-never-submitted
type: issue
status: resolved
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
- Since: MVP（指令写入方式自始如此）· Still occurring: no（本 issue 已修）
- Severity: 高——澄清/答疑的"agent 先开口"流程走不到第一步。

## 3. Root Cause (first principles)

1. 提交与换行是两个键：Claude Code 的输入框把 LF（`\n`）当作**框内换行**，
   提交需要 CR（`\r`，即 Enter 键的实际字节）。
2. 最小机制（第一轮修复后修正——结尾字节改 `\r` 无效，真机制有两层时序）：
   `tools/whiteboard/src/sessionManager.ts` 在 **spawn 瞬间**一次性写入
   整段指令。(a) 此刻终端仍处内核 cooked 模式（ICRNL 开），结尾的 `\r`
   在 CLI 进入 raw 模式前就被内核翻译回 `\n`；(b) 即便字节存活，整段文本
   与结尾字节同属一次突发写入，Claude Code 的**粘贴检测**把它们识别为一次
   粘贴——粘贴结尾的回车按设计不触发发送（防粘贴即发）。
3. 真根因是**提交键的时序**：它必须是晚于 CLI 就绪、独立于指令突发的一次
   `\r`；不是结尾字节选错（第一轮的误诊——那只修给了测试看），也不是指令
   太长。第一轮回归测试只断言发出的字节，观察不到内核行规程与粘贴检测这
   两层，故通过而无效。

- Introduced by: MVP 的首次指令写入实现。此前无 TUI 可见（issue-00009），
  替身脚本按行读 stdin、LF 即够，所以从未暴露。

## 4. Scope (same-cause sweep)

| Site | Same pattern | Affected | Action |
| --- | --- | --- | --- |
| `tools/whiteboard/src/sessionManager.ts` spawn 即写指令+结尾字节 | yes | yes | 指令不带提交字节；首个输出后延迟独立发 `\r` ×2（幂等保险） |
| `/api/terminal` 的用户击键转发（`server.ts`） | no | no | xterm 的 Enter 是用户按下的独立按键、CLI 已在 raw 模式，原样转发正确 |

## 5. Reproduction (test-first)

- Failing test（第二轮，第一轮的字节断言用例已随误诊删除）：
  `test/sessionManager.test.ts::submitting the instruction` 三例——
  `writes the instruction with no submit byte of its own, and waits for the
  CLI`、`presses Enter once the CLI has spoken, and once more as insurance`、
  `presses nothing into a session that has already ended`——对第一轮代码
  首跑均失败于 `expected ['first line\nsecond line\r'] to deeply equal
  ['first line\nsecond line']`。最锐利的旁证：AC-12.2 的既有真 PTY 用例
  在新写法下先超时失败（子进程把指令末行与 `ping` 读成一行），等待提交
  后通过——证明 spawn 时指令确实不再被"提交"。

## 6. Fix

- Change（第二轮）: 指令正文在 spawn 时写入（不带结尾提交字节）；提交键
  改为**就绪后独立发送**——等 PTY 产出首批输出（CLI 已进 raw 模式）再延迟
  数百毫秒发一次 `\r`，其后再补发一次作保险（输入框为空时回车是空操作，
  幂等安全）。延迟常量代码持有，测试可注入。
- Why this addresses the root cause and not the symptom: 补的是"提交键
  作为独立按键事件"的时序本身——躲开 cooked 模式的 ICRNL 翻译与粘贴分组
  两层机制；第一轮只改字节，两层照吃不误。

## 7. Verification

- §5 的回归测试通过（第二轮）：spawn 只写指令原文、首个输出后 1×/2× 延迟
  各发一次独立 `\r`、已退出不发；既有真 PTY 用例改为等待提交后的末行再
  交互。套件 662 测试全绿、覆盖率四项不降。
- 已知观察：静默不输出的 CLI 永远等不到提交（TUI 均有横幅，未加兜底
  定时器）；提交对用户输入是盲发的（首输出后 0.8s 内用户抢打的字符会被
  一并提交），按"输入框为空回车无操作"的幂等性接受。
- **真 CLI 实测（收口证据，克隆上执行）**：对 draft idea 发起澄清后
  **4.5s 内**出现工作指示（esc to interrupt/⏺），输出流可见 claude 已在
  执行指令（"I'll start by reading the document…"、逐条读取目标文档与
  骨架条款）——指令即发即答成立。另证双击保险的价值：克隆目录触发了
  claude 的信任目录对话框，第一次回车被它消费，第二次回车完成提交。

## 8. Follow-through

- Detection gap: 同 issue-00009——替身与假 PTY 都不区分 LF/CR；新测试把
  结尾字节钉成契约。
- Doc verdict: **code was non-conformant** —— AC-11.2 的「作为首个输入
  发送」本就蕴含提交；design-00001 §7 加一句注记，spec 不动。
- Residual state: none。

## Links

- Blocks: spec-00001-docs-whiteboard
- Related: issue-00009-terminal-size-never-reaches-the-pty · issue-00012-stop-cannot-end-a-process-that-ignores-sighup
