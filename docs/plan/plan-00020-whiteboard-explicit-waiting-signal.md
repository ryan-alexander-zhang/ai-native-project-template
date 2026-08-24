---
id: plan-00020-whiteboard-explicit-waiting-signal
type: plan
status: open
implements: [spec-00003-whiteboard-parallel-sessions]
---

# Plan: 等待输入的显式信号通路（OSC 777 锁存）

> 对 `spec-00003` 第十八轮增量的实现：FR-6 双通路 + 锁存，交付范围 =
> `spec-00003-AC-6.6` … `spec-00003-AC-6.13` 全部 8 条新 AC 及既有
> AC-6.1 … AC-6.5 的回归（AC-6.2 本轮收紧了 Given），落
> `decision-00011` 的各项裁决；纯服务端 `sessionManager` 改动 + 既有
> 页面行为回归，无页面新功能；含配套的 design-00001 修订轮与
> spec-00004 括注校正轮。

## Design

Links only——修订内容按 `decision-00011` §5 的清单，在 T1 修订轮里落笔：

- [design-00001-docs-whiteboard](../design/design-00001-docs-whiteboard.md)
  —— §5 会话生命周期的注册表级规则增补：信号识别（`ESC]777;notify;`
  前缀匹配、跨输出块缓冲及其上界）、锁存状态机（置位/幂等/解除三事件）、
  与静默判定的定序（信号识别先于输出解除）；§7 事件来源据实补记等待
  标志翻转（待 T1 修订轮）。

## Tasks

T1 与 T4 是文档轮，互相独立；T2 依赖 T1；T3 收口。**T1 未接收前不得
开写 T2 的代码**（不对 `draft` 文档写码的既有纪律）。

- **T1 — design-00001 修订轮**：板上转 `draft` → §5 增补信号识别与锁存
  状态机、§7 补记事件来源（见 Design）→ 审计 → 接收。
- **T2 — 实现**（`tools/whiteboard/src/sessionManager.ts` 及其测试，
  无页面改动）(spec-00003-FR-6)：PTY `onData` 中先于静默重臂做序列识别
  （含跨块缓冲）；识别即置位并锁存，重复信号幂等，进程已退出不置位；
  锁存期间输出不解除；`write()`（任一次按键）与会话结束解除锁存并重臂
  静默通路；含信号序列的输出块不触发解除。
- **T3 — 测试与验收收口**：按 `spec-00003-AC-6.6` … `AC-6.13` 各落一测，
  每测带 `// <AC id>` 溯源标注；AC-6.1 … AC-6.5 既有测试回归（AC-6.2
  的 Given 收紧后按需调整）；`npm test`、typecheck、覆盖率门不降；写
  record（`parent` 指向本 plan，
  `verifies: [spec-00003-whiteboard-parallel-sessions]`）列 FR-6 本轮
  全部 AC，以本 plan 过 resolved 门收口。
- **T4 — spec-00004 括注校正轮**：FR-2 括注的翻摆前提原地校正为「信号
  到达前的窗口，以及不发该序列的 CLI」（`decision-00011` §5），判重
  语义与 AC 均不动——除括注、§6 未作答条目的注记与 Links 追加
  `decision-00011` 外一字不动，走最小修订轮。

## Detailed Acceptance Path

1. T1 文档轮完成 → verify: design-00001 重新 `active` 且 §5 含信号识别
   与锁存状态机、§7 含等待标志翻转来源。
2. T2 落地 → verify: `spec-00003-AC-6.6` … `AC-6.13` 对应测试全部通过，
   AC-6.1 … AC-6.5 回归通过。
3. 全量测试、typecheck、覆盖率门全绿 → verify: 命令退出码与阈值，无门槛
   下调。
4. T4 完成 → verify: spec-00004 FR-2 括注与 `decision-00011` §5 一致，
   其余一字不动。
5. record 列全本轮 AC，本 plan 经 `open → resolved` 放行 → verify:
   resolved 门通过（`rule-00001-BR-25`）。

## Out of Scope

- 以信号通路取代静默通路、OSC 9 等其他通知约定、标题正文解析
  （`spec-00003` §6 / `decision-00011` §3）。
- 长时间未作答的周期性再提醒（`decision-00011` §2 第 7 条，另立条目）。
- 页面侧（`web/`）任何新行为：spec-00004 的判重、徽标呈现均不改，仅
  受益于标志不再翻摆。
