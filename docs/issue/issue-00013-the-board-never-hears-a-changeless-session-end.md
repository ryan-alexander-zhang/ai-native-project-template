---
id: issue-00013-the-board-never-hears-a-changeless-session-end
type: issue
status: open
blocks: [spec-00001-docs-whiteboard]
---

# Issue: 无变更结束的会话，白板永远不知道

> 会话不写任何 docs 文件地结束（如用户 `/exit`）时，没有任何信号到达前端：
> 徽章卡在 `running`、推进/澄清/答疑三入口锁死、Stop 按钮还在——点了只换来
> 服务端如实的「no running session」报错。

## 1. Problem

- Observed: `/exit` 正常退出会话后，会话徽章仍 `running`、发起入口禁用、
  Stop 可见；点 Stop 得到右下角报错 `there is no running agent session to
  stop`（服务端 404，它是对的——错在前端的过时状态）。
- Expected: 会话结束即刷新且会话状态随之更新——spec-00001-FR-12「会话进程
  退出时终端呈现结束状态且白板刷新节点图」，useBoard 注释也自陈"三个触发
  来源之一是会话结束"。
- Trigger: 任何不产生 `docs/` 变更的会话结束（`/exit`、纯答疑对话、启动即
  退）。

## 2. Impact

- Affected: 全部三种会话的无变更结束路径；表现为 issue-00010 式锁死的
  前端复刻——服务端明明空闲，前端拒绝发起任何新会话，只能整页重载解锁。
- Since: MVP（推进会话几乎必写文件，路径长期未走到）· Still occurring: yes
- Severity: 中高——答疑恰恰常常"聊完不改"，第八轮起该路径变成日常。

## 3. Root Cause (first principles)

1. 前端得知会话结束的唯一现实通路是"会话产出 commit → docs 变化 → watcher
   推送 → refresh"；无变更结束时该链条第一环就断了。
2. 最小机制：`tools/whiteboard/src/server.ts` 的会话收尾只在有产出时落
   commit，从不主动广播事件通道；`tools/whiteboard/src/watcher.ts` 只监听
   `docs/**`；且即便 refresh 发生，
   `tools/whiteboard/web/src/useBoard.ts:44-55` 的 `refresh()` 只重取图，
   从不重取 `GET /api/sessions`——会话状态只在发起时与整页加载时取一次。
3. 真根因是**"会话结束"这个刷新触发源从未被接线**（useBoard.ts:38-39 的
   注释写了三源，实现只有两源），叠加 refresh 的重取范围不含会话状态；
   不是 Stop 的 404 判定错（它是对的），也不是终端 exit 提示缺失（缓冲里
   有那行字，但那是给人看的字节，不是给前端的信号）。

- Introduced by: MVP 的刷新通路（第七轮 issue-00007 落地 watcher 推送时，
  "会话结束"触发源以"会话必产生 commit"为隐含前提成立）；第八轮的答疑
  使无变更结束成为常态，前提失效。

## 4. Scope (same-cause sweep)

| Site | Same pattern | Affected | Action |
| --- | --- | --- | --- |
| `tools/whiteboard/src/server.ts` 会话收尾不广播 | yes | yes | 收尾后无条件经事件通道广播（有无 commit 皆然） |
| `tools/whiteboard/web/src/useBoard.ts:44-55` refresh 不重取会话 | yes | yes | refresh 重取 `GET /api/sessions` 并更新状态 |
| 有变更结束的会话 | yes | 曾侥幸 | 靠 commit 触发 watcher 间接生效；接线后不再依赖侥幸 |
| 整页重载（useBoard 挂载时取一次会话） | no | no | 本就直取最新 |

## 5. Reproduction (test-first)

- Failing test: `test/server.test.ts::signals the end of a session that
  changed nothing` —— 首跑失败于 `expected +0 to be 1`（等满 10s，无变更
  结束确实一声不吭；修复后恰一次信号，400ms 后仍是一次——收尾不是刷新
  风暴）；`web/test/refresh.test.tsx::shows the end state, hands the
  entries back and takes the stop away` —— 首跑失败于找不到 `exited`
  徽章、Stop 仍在屏上，即上报症状原文。

## 6. Fix

- Change: (a) 服务端会话收尾后**无条件**广播事件通道（复用变更推送的无
  载荷信号，三触发源自此真正共用一条通路，design-00001 §6 的既有承诺）；
  (b) `refresh()` 的重取范围加入会话状态。spec 修订：FR-12 补"无 docs
  变更的结束同样触发刷新"，新增 AC-12.8；CONTEXT.md「刷新」定义的重取
  范围补会话状态。
- Why this addresses the root cause and not the symptom: 接上缺失的触发源
  与缺失的重取项；不是在前端猜超时、也不是把 404 静音。

## 7. Verification

- §5 的回归测试通过（AC-12.8 两半：线上的信号 + 用户看到的复位）；实现
  取径——会话收尾在 `finally` 中无条件复用 watcher 的去抖扇出发信号
  （有 commit 的会话通常两次刷新，刷新幂等，接受）；`refresh()` 并行重取
  graph 与会话状态。两处冻结会话状态的测试桩按"服务端才是权威"改为随
  动作翻转。套件 664 测试全绿、覆盖率四项不降、契约常绿。
- **待回填**：实测 `/exit` 后徽章即转 `exited`、入口恢复、Stop 消失
  （域主执行），确认前保持 `open`。

## 8. Follow-through

- Detection gap: AC-12.3/12.4 的既有测试都用会写文件的会话，无变更结束
  从未被采样；AC-12.8 即护栏。
- Doc verdict: **the doc was incomplete** —— FR-12 与 CONTEXT「刷新」随本
  issue 修订。
- Residual state: none。

## Links

- Blocks: spec-00001-docs-whiteboard
- Related: issue-00007-the-board-never-hears-about-disk-changes（同形：又一个"白板听不见"）· issue-00010-a-stuck-session-locks-the-board
