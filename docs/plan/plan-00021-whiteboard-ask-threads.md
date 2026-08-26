---
id: plan-00021-whiteboard-ask-threads
type: plan
status: resolved
implements: [spec-00005-whiteboard-ask-threads]
---

# Plan: 答疑线程——问完即走的只读问答，终端答疑退役

> 对 `spec-00005-whiteboard-ask-threads` 全部条目的实现（交付范围 =
> 整份 spec，`rule-00001-BR-24`），落 `decision-00012` 的各项裁决；
> 含配套的两份 design 修订轮，与四份既有文档（spec-00001、spec-00003、
> spec-00004、rule-00001）的交接修订轮。

## Design

Links only——修订内容按 `decision-00012` §5 的清单，在 T1 修订轮里落笔：

- [design-00001-docs-whiteboard](../design/design-00001-docs-whiteboard.md)
  —— headless 执行通路（捕获输出，无 PTY）、问题列表存储（位置与
  文件形态、接续标识的持有与捕获口径）、claude 缺省 headless 声明
  （首调/接续两命令形态、问题占位、只读旗标集）、注册表第二形态
  （不占文档、无等待判定、终止照常）、启动核销（待 T1 修订轮）。
- [design-00002-whiteboard-ui](../design/design-00002-whiteboard-ui.md)
  —— 浮窗与编辑器双入口与问题输入、编辑器第三视图态（问题列表：
  逐项状态、点开问答、追问、失败重发、缓冲保留）、节点标记的多会话
  口径与激活分支、会话面板逐项终止入口、面板与通知点击的答疑导航、
  前端 doc→session 映射与入口锁推导的改造（待 T1 修订轮）。

## Tasks

T1、T2 是文档轮，先行且互相独立；T3 依赖 T1、T2；T4 依赖 T3；T5
收口。**T1、T2 未接收前不得开写 T3/T4 的代码**（不对 `draft` 文档
写码的既有纪律；`decision-00012` 与 `spec-00005` 的接收先于一切）。

- **T1 — design-00001 与 design-00002 修订轮**：板上转 `draft` →
  按上节清单增补 → 审计 → 接收；两份 design 的 `informs` 增列
  `spec-00005-whiteboard-ask-threads`（回链）。
- **T2 — 既有文档交接修订轮**（各归各的修订轮，逐份走
  draft → 审计 → 接收；完整口径以 `spec-00005` §1 交接清单为准）：
  - `prd-00001`：答疑描述（「按对话结论修订」）改写为只读问答。
  - `spec-00001`：FR-47 改写为向 `spec-00005` 的交接（答疑不再是
    终端会话，`spec-00001-AC-47.1` 改写为交接守卫、`AC-47.2` …
    `AC-47.5` 退役保留为历史 record 的归属锚点，Story S9 价值句
    改写）；FR-14 移除「答疑一会话一 commit」半句且
    `spec-00001-AC-14.7` 退役保留、其测试移除；FR-18 与
    `spec-00001-AC-18.1` 对答疑 kind 排除；`spec-00001-AC-55.1`
    改写为 headless 首调或换例。
  - `spec-00003`：FR-1 前件与终端通道半句、FR-2 文档占用、FR-6 等待
    判定对答疑 kind 排除；FR-4 点击半句、FR-5 终止入口位置扩展与
    首会话自动呈现（`spec-00003-AC-5.4`）对答疑不触发、FR-7 的
    commit 收尾半句、FR-8 快照、FR-10 激活半句逐条登记交接。
  - `spec-00004`：FR-2 等待通知的派生不适用、FR-5 点击呈现的答疑
    分支登记交接。
  - `rule-00001`：BR-21 改写为只读问答，`rule-00001-AC-21.3` 退役
    保留、其测试移除。
  - `CONTEXT.md` 七处修订（含「答疑」_Avoid_ 去「问答」、「会话前
    快照」的「四种皆然」收窄）与三条新词条随 `spec-00005` 接收落笔。
- **T3 — 服务端实现**（`tools/whiteboard/src` 及其测试）
  (spec-00005-FR-1, FR-2, FR-4, FR-5, FR-6, FR-7, FR-8)：headless
  声明（首调/接续两形态、占位与只读旗标）解析与启动校验；问题列表
  存储（按文档一份、逐线程问答与时刻、接续标识、重启保持、启动核销
  进行中为失败态）；headless 调用执行（只读旗标上命令行、捕获输出、
  零退出判已答、非零判失败、面板终止、无超时判败）；注册表接入
  （kind 答疑不占文档、总上限适用、等待判定排除、结束事件、历史
  条目含回答文本）；答疑接口（首调、追问、重发）与全部拒绝口径
  （线程内串行、异常节点、无声明）；终端答疑通路退役。
- **T4 — 前端实现**（`tools/whiteboard/web/src` 及其测试）
  (spec-00005-FR-1, FR-2, FR-3, FR-6, FR-7, FR-9)：浮窗入口改
  headless（终端答疑入口退役）与编辑器提问入口新增、问题输入与
  agent 选择（可选集收窄）；编辑器第三视图态（问题列表：逐项状态、
  点开问答、控制序列剥离后 Markdown 渲染、追问、失败/终止态与重发、
  缓冲保留）；进行中呈现与刷新恢复；节点标记的多会话口径与激活
  分支——**前端现按「一文档至多一会话」建 doc→session 映射并由之
  推导入口锁，必须改造**；会话面板逐项终止入口；面板与通知点击的
  答疑导航（含不在图上/不在列表的就近处置）；答疑永不入终端回落；
  全部 agent 无声明时入口缺失。
- **T5 — 测试与验收收口**：按 `spec-00005-AC-1.1` … `AC-9.7` 全部
  41 条各落一测，每测带 `// <AC id>` 溯源标注；`npm test`、typecheck、
  覆盖率门不降；写 record（`parent` 指向本 plan，
  `verifies: [spec-00005-whiteboard-ask-threads]`）逐 AC 列行，以本
  plan 过 resolved 门收口（`rule-00001-BR-25`）。

## Detailed Acceptance Path

1. T1、T2 文档轮完成 → verify: 两份 design 重新 `active` 且 `informs`
   含 `spec-00005`；prd-00001/spec-00001/spec-00003/spec-00004/
   rule-00001 的交接改写各自重新 `active`；`CONTEXT.md` 七处修订与
   新词条在位。
2. T3、T4 落地 → verify: `spec-00005-AC-1.1` … `AC-9.7` 对应测试全部
   通过，原 `spec-00001-AC-47.x`/`AC-14.7`/`AC-18.1`/`AC-55.1` 与
   `rule-00001-AC-21.3` 的测试按各修订轮结论改写、移交或删除。
3. 全量测试、typecheck、覆盖率门全绿 → verify: 命令退出码与阈值，
   无门槛下调。
4. record 列全 9 条 FR 的 41 条 AC，本 plan 经 `open → resolved`
   放行 → verify: resolved 门通过（`rule-00001-BR-25`）。

## Out of Scope

- `spec-00005` §6 的全部条目（修订能力、清空入口、无输出超时、搜索
  导出与配额、多用户协调、跨重启存续、其余三种会话的改动）
