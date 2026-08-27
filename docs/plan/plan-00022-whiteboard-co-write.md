---
id: plan-00022-whiteboard-co-write
type: plan
status: resolved
implements: [spec-00006-whiteboard-co-write, rule-00001-BR-28, rule-00001-BR-29, rule-00001-BR-30]
---

# Plan: 共写会话——第五种会话种类的实现

> 对 `spec-00006-whiteboard-co-write` 全部条目与 `rule-00001-BR-28` …
> `BR-30` 的实现（交付范围 = 整份 spec 加三条 BR，`rule-00001-BR-24`），
> 落 `decision-00015` 的各项裁决；含配套的两份 design 修订轮，与既有
> 文档（spec-00001、spec-00003、spec-00004、流程配置注释）的交接修订轮。

## Design

Links only——修订内容按 `spec-00006` §1 交接清单与 §5 设计表，在 T1
修订轮里落笔：

- [design-00001-docs-whiteboard](../design/design-00001-docs-whiteboard.md)
  —— `SessionKind` 第五种（共写）、共写任务指令载荷（README、条目
  文法、写域约束、材料段）、收束过滤（合式判定、复原、单次 commit）
  与校验挂点、新建共写模式的建档通路、新 agent 启动形态的验证步骤
  （待 T1 修订轮）。
- [design-00002-whiteboard-ui](../design/design-00002-whiteboard-ui.md)
  —— 工作区布局（编辑器右槽 + 终端底部同屏）、编辑器只读态与缓冲
  重载、手改注记的注入点、新建对话框的模式选择与材料区、浮窗共写
  入口（待 T1 修订轮）。

## Tasks

T1、T2 是文档轮，先行且互相独立；T3 依赖 T1、T2；T4 依赖 T3；T5
收口。**T1、T2 未接收前不得开写 T3/T4 的代码**（不对 `draft` 文档
写码的既有纪律；`decision-00015`、`spec-00006` 与 `rule-00001` 本轮
修订已 `active`，接收先于本 plan 开启）。

- **T1 — design-00001 与 design-00002 修订轮**：板上转 `draft` →
  按上节清单增补 → 审计 → 接收；两份 design 的 `informs` 增列
  `spec-00006-whiteboard-co-write`（回链）。
- **T2 — 既有文档交接修订轮**（各归各的修订轮，逐份走
  draft → 审计 → 接收；完整口径以 `spec-00006` §1 交接清单为准）：
  - `spec-00001`：FR-3 浮窗入口枚举增共写；FR-13 的「不做 git 回滚
    兜底」半句对共写改写、§6「写权限范围的用户自定义配置」排除相应
    改写；FR-14 的 commit 会话种类枚举增共写；FR-19 的拒绝枚举增
    共写发起；FR-42 登记共写缓冲重载例外；FR-53 增共写模式分支
    （三项拒绝移至确认时评估、确认即建档）；FR-55 的种类枚举增共写。
  - `spec-00003`：FR-1 … FR-3 的终端种类枚举并入共写（同文档互斥、
    逐会话通道、全局上限、id 保留）；FR-6 等待判定、FR-8 会话前
    快照、FR-9 离场、FR-10 对共写适用的枚举扩写。
  - `spec-00004`：等待输入与结束通知对共写适用——T2 查实其正文无任何
    会话种类枚举（全部搭 `spec-00003` 载荷），无需改写，零改动收项。
  - `whiteboard.config.yaml` 注释：新增 agent/启动形态进配置前须验证
    共写形态下的写域行为与授权交互（`spec-00001-AC-13.2` 验证先例的
    扩展）。
  - `CONTEXT.md` 变更集已随 `spec-00006` 接收落笔（词条共写与四词条
    修订），T2 不再动它。
- **T3 — 服务端实现**（`tools/whiteboard/src` 及其测试）
  (spec-00006-FR-1, FR-2, FR-3, FR-6, FR-7, FR-8, FR-9, FR-10,
  rule-00001-BR-28, BR-29, BR-30)：`SessionKind` 增共写；发起接口与
  状态合法性判定（`draft` 或 `open` work item，非法拒绝并说明原因）；
  任务指令组装（目标路径与类型、文件夹 README、条目文法段、写域
  约束、材料段与蒸馏要求）；新建共写模式（确认时三项拒绝、通过即
  建档 commit、接续会话）；材料输入拼装；收束过滤（合式 `reference`
  判定：BR-18 取号且不撞保留号、front matter 有效且 type 为
  reference；目标文档 `id`/`status` 改动滤除；越界复原、域内照常）；
  收束单次 commit 与 FR-17 口径校验；会话期对目标文档状态切换与评审
  动作的拒绝；注册表/互斥/上限/快照/历史/agent 选择的共写接入。
- **T4 — 前端实现**（`tools/whiteboard/web/src` 及其测试）
  (spec-00006-FR-1, FR-2, FR-3, FR-4, FR-5, FR-9, FR-10)：浮窗共写
  入口（不按 status 条件化、异常节点缺失）；新建对话框模式选择与
  材料区；工作区——编辑器面板与终端同屏、agent 写入后缓冲重载、
  非等待期只读、等待期可编辑保存、下一轮输入附手改注记；发起与
  状态动作的拒绝呈现。
- **T5 — 测试与验收收口**：按 `spec-00006-AC-1.1` … `AC-10.4` 全部
  44 条（修订轮增至此数，原文 31 为过期计数——核验轮据实校正）与
  `rule-00001-AC-28.1` … `AC-30.5` 全部 12 条各落一测（可
  共用测试，逐 AC 溯源标注 `// <AC id>`）；`npm test`、typecheck、
  覆盖率门不降；写 record（`parent` 指向本 plan，`verifies` 覆盖交付
  范围）；子代理按 `CLAUDE.md` §8 核验每条 GWT 有过测后促本 plan
  `resolved`。

## Detailed Acceptance Path

1. T1/T2 的每份修订文档回到 `active`，全量文档零解析诊断 → verify:
   `npm test` 契约测试通过。
2. 共写全链路在真实 CLI 上走通（发起 → 材料 → 多轮 → 手改 → 收束
   过滤 → 单次 commit → 校验）→ verify: 集成测试与手工冒烟。
3. 交付范围内每条 AC 在 record 有通过行 → verify: record 验收清单
   （`parent` 指向本 plan）。
4. 本 plan 过 `open → resolved` → verify: resolved 门放行
   （`rule-00001-BR-25`）。

## Out of Scope

- agent 每轮写入的差异标示、修订轮版本 diff、材料附件存储与配额、
  编辑器内嵌补全、白板自建抓取（`spec-00006` §6 的既有排除）。
- `prd-00001` 的能力清单补共写一节——属其自身修订轮，随下次 prd
  修订登记，不在本 plan。
