---
id: issue-00007-the-board-never-hears-about-disk-changes
type: issue
status: open
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

- 端到端复现见 §1（plan-00006 实测 (f) 的记录与截图）。失败测试待修复轮落地：
  服务端「docs/ 变动 → WS 广播」与前端「收到广播 → 重取并保持呈现状态」各一。

## 6. Fix

- Change: 待裁定，两个方向——(a) 按 design 补通道：chokidar → WS 广播 → 前端
  重取（呈现状态按 id 保持，plan-00006 U2 的裁定即适用）；(b) 修改 design-00001
  收回推送承诺，NF 明确为「手动刷新页面即可见」。倾向 (a)：人机并行是白板的
  核心场景。域主裁定后另立 plan。

## 7. Verification

- 待填。

## 8. Follow-through

- Detection gap: 没有任何测试覆盖「外部变动可见」——NF 不写 GWT 的取舍使然；
  修复轮应至少给通道本身立契约测试。
- Doc verdict: 待裁定方向后定——(a) 则 code was non-conformant；(b) 则 doc was
  wrong，改 design-00001 §1/§6。
- Residual state: none。

## Links

- Blocks: design-00001-docs-whiteboard（§1、§6 的推送承诺待对账）
- Related: plan-00006（实测 (f) BLOCKED 的出处）、record-00005（待建）
