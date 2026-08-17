---
id: issue-00012-stop-cannot-end-a-process-that-ignores-sighup
type: issue
status: open
blocks: [spec-00001-docs-whiteboard]
---

# Issue: Stop 杀不掉无视 SIGHUP 的进程，终止请求挂起、白板仍锁死

> FR-49 承诺"终止始终可达"，但终止只发一次 SIGHUP：进程若忽略它，DELETE
> 请求永远等不到退出收尾，Stop 看起来没反应，单会话约束继续锁死全部发起
> 入口——record-00009 观察项 2 预告的坑，真实使用第一天就踩中。

## 1. Problem

- Observed: 会话卡住时点 Stop 无效果，会话仍 running，白板保持锁死。
- Expected: 终止对任何进程最终生效——spec-00001-FR-49「立即结束该会话
  进程」不应取决于对端是否礼貌。
- Trigger: 会话进程忽略或延迟处理 SIGHUP（交互式 CLI 常见——真实 claude
  CLI 处于工作/等输入态时即如此）。

## 2. Impact

- Affected: Stop 的全部使用场景；恰恰是"会话卡死"这个 Stop 存在的理由
  本身。
- Since: FR-49 落地（issue-00010 修复）· Still occurring: yes
- Severity: 高——issue-00010 的锁死症状在最需要解锁的场景下复活。

## 3. Root Cause (first principles)

1. 终止 = 发信号 + 等退出；可忽略的信号让"等"变成永远。
2. 最小机制：`tools/whiteboard/src/pty.ts` 的 `kill: () => pty.kill()`
   ——node-pty 在 Unix 上默认发 **SIGHUP**，可被捕获/忽略；
   `DELETE /api/sessions` 等待退出收尾后才响应（issue-00010 修复的既定
   取舍），于是信号被忽略时请求悬置、`status` 停在 `running`、FR-18 的
   互斥继续生效。
3. 真根因是**无升级路径**（可忽略信号后面没有不可忽略的 SIGKILL 兜底），
   不是 await 设计错了，也不是前端按钮没接线。

- Introduced by: issue-00010 的修复（commit 1e2f8c4c）首次引入终止能力，
  当时已把"无 SIGKILL 升级"记为 record-00009 观察项 2 的已知取舍。此前
  没有终止能力，缺陷无从发生。

## 4. Scope (same-cause sweep)

| Site | Same pattern | Affected | Action |
| --- | --- | --- | --- |
| `tools/whiteboard/src/pty.ts` `kill()` 单发 SIGHUP | yes | yes | 宽限期后升级 SIGKILL |
| `tools/whiteboard/src/server.ts` DELETE 等待收尾 | yes | yes | 保持 await——升级保证等待有界 |
| 服务停机路径（不杀会话，issue-00010 §4 观察行） | yes | no | 本轮仍不改；停机孤儿与 Stop 是两件事 |

## 5. Reproduction (test-first)

- Failing test: `test/server.test.ts::ends a session whose process ignores
  the polite signal` —— 真 PTY 跑 `bash -c "trap '' HUP; …; sleep 60"`
  （pid 文件定位，SIG_IGN 穿透 exec），首跑失败于
  `Test timed out in 30000ms`——DELETE 永不返回，即本 issue 症状原文；
  修复后 ~0.5s 内返回 200、`status: exited`、`kill(pid, 0)` 证明进程
  不复存在（测试注入 200ms 宽限）。

## 6. Fix

- Change: 终止升级——先礼后兵：SIGHUP（保留既有语义，给 CLI 收尾机会）→
  宽限期（秒级，代码持有）内未退出 → SIGKILL（不可忽略）。退出收尾仍由
  唯一的 `onExit` 触发，恰一次不变。spec 补 AC-49.10（无视信号的进程仍
  被结束）。
- Why this addresses the root cause and not the symptom: 补的是升级路径，
  让"等退出"必然有界；不缩短礼貌信号的机会窗口。
- Alternatives rejected: DELETE 改为不等待立即 200——用户拿不到最终态，
  AC-49.2 的 commit 可观察性丢失；只治了挂起没治杀不掉。

## 7. Verification

- §5 的回归测试通过（AC-49.10）；生产宽限 `KILL_GRACE_MS = 3_000`——Stop
  是用户等在原地的请求，宽限必须是秒级：3s 够 CLI 听到 SIGHUP 收尾，又短
  到 Stop 仍像个按钮；升级定时器一次性布防、进程退出即清、`unref` 不吊住
  进程；重复 kill 不重置时钟。恰一次收尾（AC-49.6）的既有测试未回归。
  套件 660 测试全绿、连跑无 flake、覆盖率四项不降反升。
- **待回填**：实测「卡死会话 → Stop → 宽限后解锁」（域主执行），确认前
  保持 `open`。
- 已知同形残留（非本轮范围）：SIGKILL 只达会话直接子进程，CLI 再往下
  spawn 的孙进程可能幸存——与 issue-00010 §4 停机孤儿同形，需要时另立
  issue。

## 8. Follow-through

- Detection gap: AC-49.1 的既有测试用会退出的假会话，从未有"不听话的
  进程"；新测试即护栏。
- Doc verdict: **the doc was missing the hostile case** —— FR-49 随附
  AC-49.10；design-00001 §7 的 DELETE 行注明升级语义。
- Residual state: none。

## Links

- Blocks: spec-00001-docs-whiteboard
- Related: issue-00010-a-stuck-session-locks-the-board · record-00009-whiteboard-terminal-fixes-acceptance
