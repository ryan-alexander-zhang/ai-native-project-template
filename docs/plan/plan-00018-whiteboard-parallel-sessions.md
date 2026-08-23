---
id: plan-00018-whiteboard-parallel-sessions
type: plan
status: open
implements: [spec-00003-whiteboard-parallel-sessions]
---

# Plan: 并行 Agent 会话与会话面板

> 对 `spec-00003-whiteboard-parallel-sessions` 全部条目的实现（交付范围 =
> 整份 spec，`rule-00001-BR-24`），落 `decision-00009` 的各项裁决；含配套的
> 两份 design 修订轮与 `spec-00001` 修订轮。

## Design

Links only——修订内容按 `decision-00009` §5 的清单，在各自的修订轮里落笔：

- [design-00001-docs-whiteboard](../design/design-00001-docs-whiteboard.md)
  —— 会话注册表由单例槽位改多会话、终止/会话 API 携带会话标识、id 保留
  取号、静默阈值常数、commit 串行队列、关停终止收尾（待 T1 修订轮）。
- [design-00002-whiteboard-ui](../design/design-00002-whiteboard-ui.md)
  —— 会话面板、徽标与节点标记、提示条堆叠、终端切换（待 T1 修订轮）。

## Tasks

T1 与 T2 是文档轮，先行且互相独立；T3 先于 T4；T4 是服务端核心，T5、T6、
T8 依赖它；T7 依赖 T4–T6；T9 收口。**T1、T2 未接收前不得开写其后的代码**
（不对 `draft` 文档写码的既有纪律）。

- **T1 — design 修订轮**：`design-00001` 与 `design-00002` 走修订轮（板上
  转 `draft` → 修订 → 审计 → 接收），按上节清单落笔；两份的 `informs`
  增列 `spec-00003-whiteboard-parallel-sessions`（回链）。
- **T2 — spec-00001 修订轮**：一次修订轮完成五处交接与一处范围外清理——
  `spec-00001-FR-18` 改写为并发模型（指向 `spec-00003-FR-1` … `FR-3`）、
  `FR-49` 的入口/禁用原因拆分、`FR-50` 删除单会话复述、`FR-21` 扩展多
  会话、`FR-15` 校验键集增 `max_sessions`、§6 移除「多会话并行」条目。
- **T3 — 配置与启动校验** (spec-00003-FR-3)：`whiteboard.config.yaml`
  新增 `max_sessions: 3`；启动校验覆盖非正整数拒绝与缺键取缺省
  （`spec-00003-AC-3.4`、`AC-3.5`）。
- **T4 — 服务端多会话核心** (spec-00003-FR-1, spec-00003-FR-2,
  spec-00003-FR-3, spec-00003-FR-9)：会话注册表多例化；同目标文档互斥
  （推进的目标取来源文档）与总数上限（受理时占用、启动失败释放、先到
  先得）；发起中会话的 id 保留参与取号；会话与终止 API 携带会话标识；
  断线存续扩展到多会话；每会话独立终端通道与尺寸帧（仅呈现中的会话
  下发）。
- **T5 — 收尾串行与快照推广** (spec-00003-FR-8)：会话前快照推广到四种
  会话；会话结束 commit 与用户动作 commit 进同一串行队列；同文件残余按
  结束顺序归属；同批结束的刷新合并为一次。
- **T6 — 等待输入判定** (spec-00003-FR-6 服务端侧)：静默阈值常数（量级
  10s）、进程存活判定、状态随会话载荷下发；进程已退出不入判定。
- **T7 — 白板 UI** (spec-00003-FR-4, spec-00003-FR-5, spec-00003-FR-6,
  spec-00003-FR-7, spec-00003-FR-10)：顶栏会话面板与「运行中数/上限」
  入口、等待计数徽标（零态不渲染）；面板项点击呈现终端并定位节点（目标
  不在图上时就近处置）；终端切换与呈现状态保持；提示条通知（结束与启动
  失败，逐条堆叠）；节点会话状态标记（运行中/等待输入，非颜色可辨、只
  作用呈现层）；发起入口的逐文档/上限禁用原因说明。
- **T8 — 关停收尾** (spec-00003-FR-9)：服务端正常关停对每个运行中会话
  执行终止的收尾（结束进程、commit、历史落盘）。
- **T9 — 测试与验收收口**：按 `spec-00003-AC-1.1` … `AC-10.4` 全部 41 条
  各落一测，每测带 `// <AC id>` 溯源标注（沿 plan-00016 T2 的约定）；
  `npm test`、typecheck、覆盖率门不降；写 record（`parent` 指向本 plan，
  `verifies: [spec-00003-whiteboard-parallel-sessions]`）逐 AC 列行，以
  本 plan 过 resolved 门收口。旧 record 中随第十六轮语义改写而改记 n/a 的
  行——`spec-00001-AC-18.1` … `AC-18.3`、`AC-49.3`、`AC-49.5`、
  `AC-49.8`（record-00001/00008/00009/00010）——本 plan 的 record 须以
  新语义为其各补一行新证据。

## Detailed Acceptance Path

1. T1、T2 文档轮完成 → verify: 两份 design 与 `spec-00001` 在板上重新
   `active`，design 的 `informs` 含 `spec-00003`，`spec-00001` §6 不再
   含「多会话并行」。
2. T3–T8 落地 → verify: `spec-00003-AC-1.1` … `AC-10.4` 对应测试全部
   通过。
3. 全量测试、typecheck、覆盖率门全绿 → verify: 命令退出码与阈值，无门槛
   下调。
4. record 列全 10 条 FR 的 41 条 AC，本 plan 经 `open → resolved` 放行 →
   verify: resolved 门通过（`rule-00001-BR-25`）。

## Out of Scope

- `spec-00003` §6 的全部条目：板外通知、跨服务重启的会话存续、多用户
  协作、历史会话检索增强、同屏多终端、静默阈值可配。
- `rule-00001` 无任何修订（并行不改状态流转与类型集）。
