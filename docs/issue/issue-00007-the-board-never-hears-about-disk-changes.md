---
id: issue-00007-the-board-never-hears-about-disk-changes
type: issue
status: resolved
blocks: [design-00001-docs-whiteboard]
---

# Issue: 白板从不订阅磁盘变化——design-00001 承诺的推送刷新整条通道不存在

> design-00001 把「chokidar 监听 → WS 推送 → 前端刷新」写成已定设计并标注为
> NF 的载体，但服务端不监听文件系统、前端只有 PTY 一条 WebSocket——外部改动
> 只有手动刷新页面才可见，且刷新会丢掉下钻/选中/详情等全部界面状态。

## 1. Problem

- Observed: 白板打开期间从另一终端向 `docs/` 写入新文档，10s 后图纹丝不动；
  网络日志显示 `/api/graph` 自首屏后零请求。同时 curl 服务端已返回新文档——
  服务端数据是新的，是前端永不再问。
- Expected: design-00001 §6「chokidar 监听 `docs/**` → WS 推送前端刷新（NF
  「外部修改刷新可见」）」；§1 选型表也列了 chokidar 一行「外部修改推送刷新」。
- Trigger: 任何白板外的 `docs/` 变动（agent 会话之外的手工编辑、git 操作、
  其他工具写入）。

## 2. Impact

- Affected: 人与 agent 并行工作的核心场景——白板开着、旁边终端里改文档。
  plan-00006 U2 的落地裁定（子画布期间图刷新的状态保持）因此端到端不可触发，
  只能按构造成立。
- Since: MVP 起（chokidar 在 package.json 里但 `src/`、`web/src/`、`bin/` 无
  任何引用）。Still occurring: yes。
- Severity: 中——spec §7 NF 的字面（「刷新即可反映」）靠手动刷新页面尚能满足，
  但手动刷新会丢掉 `drilled`/`detail`/选中等全部呈现状态（实测确认），且
  design 与实现的落差会误导后续每一轮依赖「刷新会来」的设计（本轮 U2 即是）。

## 3. Root Cause (first principles)

1. 期望「磁盘变化推送到前端」，实际「前端只在自身动作后重取」。
2. 机制：`src/server.ts` 只在 `/api/terminal` 上起 `WebSocketServer`
   （server.ts:108），无任何 chokidar 调用；`web/src/` 唯一的 WebSocket 是
   `terminalSocket.ts`（PTY 通道）。
3. 真根因：设计写了通道，实现从未建——不是通道坏了，是没有通道。依赖装了、
   设计写了、代码没写，三者从未对过账。

- Introduced by: pre-dates plan-00002（MVP 实现起即缺）；design-00001 自始
  承诺。

## 4. Scope (same-cause sweep)

| Site | Same pattern | Affected | Action |
| --- | --- | --- | --- |
| 外部写入 `docs/` | yes | yes | 本 issue |
| agent 会话结束后的刷新 | 前端收到会话结束事件后主动重取 | no | 已有通路（FR-12） |
| 白板自身动作后的刷新 | 动作完成即重取 | no | 已有通路 |
| 手动刷新页面后的状态丢失 | 相邻问题：呈现状态不持久化 | yes | 修复时一并裁定（推送刷新若保持状态，此项自然缓解） |

## 5. Reproduction (test-first)

- 端到端复现见 §1（plan-00006 实测 (f) 的记录与截图）：白板开着、外部写入
  `docs/`，`/api/graph` 自首屏后零请求，而同刻 curl 已返回新文档。
- 本 issue 的缺陷是「整条通路不存在」而非「通路算错」，故没有单一的先行失败
  测试；等价的先行证据是那次实测，以及 `src/`、`web/src/`、`bin/` 中 chokidar
  零引用的检索结果。修复后的守卫是 22 条 AC 的测试集（服务端广播、前端订阅与
  刷新、断连重连、呈现状态保持），见 §7。

## 6. Fix

- Change: **域主已裁定取 (a)：补通道**（备选 (b) 是修 design-00001 收回推送
  承诺、NF 降为「手动刷新页面即可见」，未取——人机并行是白板的核心场景）。
  落地形态：chokidar 监听 → 去抖 → `WS /api/events` 广播无载荷信号 → 前端刷新
  （graph + 当前 items），呈现状态按 id 保持、所指对象消失则就近关闭。该行为
  由此升格为 `spec-00001-FR-42`…`FR-44` 及其 22 条 AC（原为不写 GWT 的非功能
  项），修复轮见 `plan-00008`。

## 7. Verification

- 22 条 AC（`AC-42.x`/`AC-43.x`/`AC-44.x` 及会话暂存的 `AC-14.5`/`14.6`）各有
  通过的测试，经未参与实现的 subagent 逐条核验、六项抽查非恒真；全套 557 测试
  绿，覆盖率 99.14%。
- 现场实测（plan-00008 第 4 条，`record-00007` 存档）：外部**增/改/删**分别在
  **432 / 468 / 306 ms** 内自动可见（FR-42 承诺 1 秒），连续 6 次写入只触发
  **1 轮**刷新；刷新确实同时重取 `graph` 与当前文档的 `items`；下钻 + 详情 +
  展开行在外部改动后全部保住；断连期间零错误提示、图与控件照常，重连后短断连
  **628 ms**、长断连 **17.7 s** 补齐（后者是 FR-43 规定的递增退避所致）。
- 修复过程中另查出并修掉两处自身缺陷：`eventSocket.retry()` 未清上一个待触发
  定时器（`close()` 关不掉它）、两个 `WebSocketServer` 绑同一 http server 时
  互相 abort 握手（改 `noServer` + 单一 upgrade 路由），均有测试。

## 8. Follow-through

- Detection gap: 没有任何测试覆盖「外部变动可见」——NF 不写 GWT 的取舍使然，
  设计写了通路、依赖装了、代码没写，三者两年多没对过账。本轮把该行为升格为
  FR-42…FR-44 并落 22 条 AC，缺口即闭合。
- Doc verdict: **code was non-conformant**——design-00001 §1/§6 的通路描述
  本身无误，实现从未落地；§6 本轮只做了细化（刷新范围、退避、不自激、零订阅
  者），不是纠错。
- Residual state: none（无数据损坏）。但留下一处**行为退化**记在
  `record-00007` 观察项：前端不再有独立的「会话结束即刷新」通路，FR-12 的那个
  时机现已依赖推送通道；推送不可用时，会话产出要等下一次动作或页面重载才可见。

## Links

- Blocks: design-00001-docs-whiteboard（§1、§6 的推送承诺待对账）
- Related: plan-00006（实测 (f) BLOCKED 的出处）、record-00005（该实测的记录）、
  plan-00008（本 issue 的修复轮）、record-00007（修复的验收，待建）
