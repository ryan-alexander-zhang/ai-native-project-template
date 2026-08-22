---
id: plan-00008-whiteboard-refresh-and-commit-scope
type: plan
status: resolved
implements: [spec-00001-FR-42, spec-00001-FR-43, spec-00001-FR-44, design-00001-docs-whiteboard, design-00002-whiteboard-ui]
---

# Plan: 变更推送与会话暂存范围——修 issue-00007 与 issue-00008

> 两个「设计写了、实现没做」的缺口一并补上：白板自己跟上磁盘变化并保住当前
> 位置（issue-00007，域主裁定取补通道），推进会话只提交自己写的东西
> （issue-00008）。

## Design

- [design-00001-docs-whiteboard](../design/design-00001-docs-whiteboard.md)
  §4（advance 暂存范围＝相对会话前快照的差集，实现约束）、§6（变更推送链路）、
  §7（`WS /api/events`）。
- [design-00002-whiteboard-ui](../design/design-00002-whiteboard-ui.md) §9
  第七轮段（三条重取通路共用一条保持状态的实现、断连沉默、就近关闭）。
- [issue-00007](../issue/issue-00007-the-board-never-hears-about-disk-changes.md)
  （方向 (a) 补通道，域主已裁）、
  [issue-00008](../issue/issue-00008-advance-commits-unrelated-dirty-docs.md)。

## Tasks

代码位于 `tools/whiteboard/`。W1 与 W2 互不依赖，可并行；W3 依赖 W2；W4 收尾。

| # | 任务 | 交付 | 覆盖 |
| --- | --- | --- | --- |
| W1 | 会话暂存范围（issue-00008） | 会话启动时取 `docs/` 快照（已脏路径 + 内容摘要），结束时以**内容差集**为暂存集（三种处置见 design-00001 §4——快照外的暂存、摘要不变的排除、摘要已变的暂存）；差集为空则不 commit。**测试先行**：先写在现实现下失败的用例——「会话前已脏的 docs 文件被卷入 advance commit」，再修 | spec `AC-14.5`/`AC-14.6`；design-00001 §4 |
| W2 | 变更推送通道（issue-00007） | 服务端 chokidar 监听 `docs/**` → 去抖合并（1 秒可见上界之内）→ `WS /api/events` 广播无载荷信号；前端订阅、收到即刷新——**重取 `GET /api/graph` 与当前选中/下钻文档的 `GET /api/docs/:id/items` 两者**（只取 graph 则 `AC-42.2`、`AC-44.x` 落不了地）；断连不报错、自动重连（递增间隔）、连接建立即刷新一次；`docs/` 之外的变化不触发；零订阅者不报错；多白板各自收到 | spec FR-42、FR-43 及其 AC；design-00001 §6/§7 |
| W3 | 刷新后保持呈现状态 | 把选中/下钻/展开/详情的保持与「就近关闭」收敛到**一条**重取通路上（三个触发来源共用），使既有的按 id 保持在推送触发下同样成立；所指对象消失时逐级就近关闭 | spec FR-44 及其 AC；design-00002 §9 |
| W4 | 测试与收尾 | 22 条新 AC 全部落测：`AC-14.5`/`14.6`、`AC-42.1`…`42.9`、`AC-43.1`…`43.4`、`AC-44.1`…`44.7`；回填两份 issue 的 §5–§7 并置 `resolved`；新建 `record-00007` 承载验收；实测见下方第 4 条 | 全部 |

## Detailed Acceptance Path

1. **每任务完成即验证**：`npm test` 全绿、`npm run typecheck` 无错、
   `npm run build` 通过；覆盖率 ≥90% 不回落；契约测试（真实 `docs/` 零诊断）
   保持常绿。
2. **新 AC**：上表 W4 的 22 条，每条有对应通过的测试。其中「1 秒内可见」
   （FR-42）由实测判定，单元测试只断言合并行为（刷新次数少于变化次数），
   不断言墙上时钟。
3. **不回归**：既有 518 条全部通过。**预期变化**（不是回归）只在会话暂存这一处，
   且要分清三类：
   - 直接调 `DocService.commitSessionChanges` 而不走会话生命周期的两个既有用例
     （`test/docService.test.ts` 的 AC-14.4 用例与 "skips the commit when the
     session changed nothing"）须插入取快照的步骤——契约多了一个入参；后者保留
     （它是干净树的场景），`AC-14.6` 是它的脏树加强版，二者并存不替代。
   - 走完整会话生命周期的用例（`test/acceptance.test.ts`、`test/server.test.ts`
     的 advance 用例）应当**一字不改地继续通过**——它们正是「快照确实在会话
     启动时取到了」的证据，若它们需要改，说明快照取在了错误的时机。
   - `AC-14.2` 的既有夹具**不需要动**：编辑动作走显式路径暂存、从不经过
     `changedPaths`，其脏文件本就在 `docs/` 之内（`docs/idea/b.md`）。真正的
     检测缺口是 advance 路径上从来没有「会话前已有脏文件」的用例——W1 补的正是
     它（issue-00008 §6/§8 的原文把缺口误记为 AC-14.2 的夹具，已同步更正）。
4. **实测核对**：用本仓真实文档——(a) 白板打开期间从另一终端新增/修改/删除
   `docs/` 下文档，**不做任何操作**观察图自动更新；(b) 下钻子画布 + 展开条目行
   + 详情面板打开时触发一次外部变化，四项呈现状态全部保住；(c) 删掉正在下钻的
   文档，观察退回顶层而非报错白屏；(d) 在 docs/ 脏工作树上发起一次推进会话
   （可让 agent 立即退出），确认**无 commit** 产生、既有脏文件原样保留——这是
   issue-00008 的现场复验；(e) 断开服务再重启，确认白板不报错且重连后补到最新
   图。任一不成立，据实记入 `record-00007`，不得默认通过。
   **(d) 的护栏**（它复现的正是 issue-00008 造成 8 文件误提交的那个动作）：
   只在 W1 落地**之后**执行；执行前后各记录完整 `git status --short` 与
   `git log --oneline -1`；若仍产生 commit，立即 `git reset --mixed HEAD~1`
   还原并据实记为 fail。另：(a)…(c) 中任何在 `docs/` 下放置或删除临时文件的
   步骤，收尾后须把 `docs/` 恢复原状、重跑契约测试（真实 docs 零诊断）并核对
   `git status` 回到基线。
5. **收尾门槛**：未参与实现的 subagent 按文档核验每条 GWT 有通过的测试，且
   范围内无 unverified 条目（每条 FR 的每条 AC 都被 record 行引用；本轮尤其
   要核对 `AC-29.6`/`AC-38.5` 在 record-00003/00005 中的既有引用未被打断）；
   `record-00007` 建好并链上 GWT id、两份 issue 回填完毕后本 plan 方可
   `resolved`。任何 gap 阻塞 `resolved`。
