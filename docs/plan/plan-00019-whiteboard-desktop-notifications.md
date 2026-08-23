---
id: plan-00019-whiteboard-desktop-notifications
type: plan
status: resolved
implements: [spec-00004-whiteboard-desktop-notifications]
---

# Plan: 桌面通知——离场时叫回用户

> 对 `spec-00004-whiteboard-desktop-notifications` 全部条目的实现（交付
> 范围 = 整份 spec，`rule-00001-BR-24`），落 `decision-00010` 的各项裁决；
> 纯前端，无服务端改动；含配套的 design-00002 修订轮与 spec-00003 修订轮。

## Design

Links only——修订内容按 `decision-00010` §5 的清单，在 T1 修订轮里落笔：

- [design-00002-whiteboard-ui](../design/design-00002-whiteboard-ui.md)
  —— 顶栏「桌面通知」开关控件、离场判定口径（可见性与焦点两个信号）、
  通知触发/补发/替换标识、点击回跳通路（待 T1 修订轮）。

## Tasks

T1 与 T2 是文档轮，先行且互相独立；T3 依赖 T1；T4 收口。**T1 未接收前
不得开写 T3 的代码**（不对 `draft` 文档写码的既有纪律）。

- **T1 — design-00002 修订轮**：板上转 `draft` → 增补桌面通知一节（开关
  控件形态、离场判定的两信号口径、通知触发点与同会话替换标识、点击回跳
  与就近处置）→ 审计 → 接收；`informs` 增列
  `spec-00004-whiteboard-desktop-notifications`（回链）。
- **T2 — spec-00003 修订轮**：§6 已追注的「板外通知」条目改写——移除
  已接管的桌面通知半边，保留「邮件等其他板外通道」为范围外条目。
- **T3 — 实现**（`tools/whiteboard/web/src` 及其测试，无服务端改动）
  (spec-00004-FR-1 … spec-00004-FR-6)：顶栏开关与权限流程（含已授权
  静默生效、关闭即安静、两态持久）；离场判定；等待输入与结束的通知触发
  （含转入离场时的补发、逐会话各一条）；同会话后到替换先到；点击回跳
  （聚焦尽力而为 + 终端呈现 + 定位选中/就近处置/会话已不在的提示）；
  内容最小化。
- **T4 — 测试与验收收口**：按 `spec-00004-AC-1.1` … `AC-6.3` 全部 21 条
  各落一测，每测带 `// <AC id>` 溯源标注；`npm test`、typecheck、覆盖率
  门不降；写 record（`parent` 指向本 plan，
  `verifies: [spec-00004-whiteboard-desktop-notifications]`）逐 AC 列行，
  以本 plan 过 resolved 门收口。

## Detailed Acceptance Path

1. T1、T2 文档轮完成 → verify: design-00002 重新 `active` 且 `informs`
   含 `spec-00004`，`spec-00003` §6 只余邮件半边。
2. T3 落地 → verify: `spec-00004-AC-1.1` … `AC-6.3` 对应测试全部通过。
3. 全量测试、typecheck、覆盖率门全绿 → verify: 命令退出码与阈值，无门槛
   下调。
4. record 列全 6 条 FR 的 21 条 AC，本 plan 经 `open → resolved` 放行 →
   verify: resolved 门通过（`rule-00001-BR-25`）。

## Out of Scope

- `spec-00004` §6 的全部条目（Web Push 升级、服务端通知、关闭后送达、
  冻结标签补发、跨窗口去重、邮件通道、逐事件偏好）。
- 服务端与流程配置：零改动（`decision-00010` §5 站立约束）。
