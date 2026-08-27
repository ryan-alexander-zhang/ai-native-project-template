---
id: spec-00006-whiteboard-co-write
type: spec
status: active
parent: prd-00001-docs-whiteboard
---

# Spec: 共写会话——文档与 agent 的双栏工作区

> 第五种会话种类：对一份 `draft` 文档（work item 亦可在 `open`）发起多轮
> agent 对话，起草或改写其正文。顶栏新建增加「共写」模式（确认即建档并
> 自动接续会话）；发起时可给材料——粘贴文本、仓内文档 id、仓库外绝对
> 路径或 URL，仓外读取经用户在终端逐动作授权。写域由收束过滤保证：仅
> 目标文档与合式新建 `reference` 进入 commit。评审门全部不动。路线与
> 取舍见 `decision-00015`。

## 1. Context

- canonical terms 见 `CONTEXT.md`：白板、节点、Agent 会话、会话面板、
  等待输入、终止、会话历史、会话前快照、新建、流程入口类型、修订轮。
- 输入：`parent` 为 [prd-00001-docs-whiteboard](../prd/prd-00001-docs-whiteboard.md)；
  全部取舍在案于 [decision-00015-whiteboard-co-write](../decision/decision-00015-whiteboard-co-write.md)。
  **同轮接收前置**：`decision-00015` 与 `rule-00001` 的本轮修订
  （`rule-00001-BR-28` … `rule-00001-BR-30`、`rule-00001-BR-21`、
  `rule-00001-BR-26`）必须与本 spec 同轮转 `active`——plan 开启前三者
  均须已接收，不得对 `draft` 条目写码写测。
- 本 spec 是 `spec-00001` … `spec-00005` 的**并列新 spec**（Sizing and
  Splitting 第 1 条），同 `parent`，不 supersede 任何一份。
- **CONTEXT.md 变更集**（本轮接收时应用）：新词条**共写（Co-write）**；
  「Agent 会话」（种类枚举四种→五种、写权限句增收束过滤）、「会话前
  快照」（终端形态三种→四种）、「终止」（按会话种类 commit 的枚举）、
  「新建」（增共写模式：确认即建档）四词条修订。
- **与既有文档的交接**（既有 spec 的种类枚举都不会自动扩到第五种，
  须逐项改写；本清单是交接的完整口径，不就地改各文档——改写属各自的
  修订轮，由实现 plan 的首个文档任务逐项登记）：
  - `decision-00008` §3 对「新建走 agent 会话」的否决行由
    `decision-00015` 推翻；`decision-00008` 其余裁决不动。
  - `spec-00001-FR-3`（浮窗入口枚举）：增共写入口。入口不按 status
    条件化——非法状态目标照常呈现、发起被拒（FR-9，沿
    `spec-00001-FR-9` 的既有先例）；异常节点不提供（`spec-00001-AC-2.4`
    口径不变）。
  - `spec-00001-FR-11`、`spec-00001-FR-12`（终端会话通道与尺寸）原样
    适用——共写是交互式 PTY 终端会话。
  - `spec-00001-FR-13` 与 `spec-00001` §6 的「写权限范围的用户自定义
    配置」排除：共写的写域收窄不经 CLI 权限配置，由 FR-6 的**收束
    过滤**承载；FR-13 的「不做 git 回滚兜底」半句对共写改写（收束过滤
    即兜底），§6 排除相应改写。
  - `spec-00001-FR-14`（一动作一 commit 的会话种类枚举）：增共写
    （FR-8，一会话一 commit）。
  - `spec-00001-FR-17`（会话产出校验）、`spec-00001-FR-41`（条目文法
    段）复用，见 FR-1、FR-8。
  - `spec-00001-FR-19`（目标文档已不存在时动作的拒绝枚举）：增共写
    发起。
  - `spec-00001-FR-31`（右侧槽位、编辑器优先）：工作区以编辑器占用
    右槽——共写 spec/rule 期间其检视面板让位，编辑器优先的既有语义。
  - `spec-00001-FR-42`（刷新不触碰编辑器缓冲）：共写会话中 agent 写入
    目标文档后的缓冲重载（FR-4）是其显式例外，在 FR-42 修订时登记。
  - `spec-00001-FR-49`（终止收尾、半成品由异常与诊断体系承接、commit
    不区分终止与自然结束）对共写适用（FR-8）。
  - `spec-00001-FR-53`（新建）：空白模式原样保留；共写模式是新分支——
    三项拒绝（id 已存在、slug 不合式、类型不在 `entry`）移至确认时
    评估，确认即建档 commit、不经编辑器保存（FR-2）；FR-53 与
    `CONTEXT.md`「新建」词条随之修订。
  - `spec-00001-FR-54`（会话历史）、`spec-00001-FR-55`（agent 选择，
    其种类枚举增共写；共写是交互式终端 kind，候选集不收窄）适用。
  - `spec-00003-FR-1` … `spec-00003-FR-3`（并发正则的终端种类枚举）：
    共写作为第四种终端形态并入——同文档互斥、逐会话终端通道、全局
    上限、服务端 id 保留；`spec-00003-FR-6`（等待输入判定）、
    `spec-00003-FR-8`（会话前快照）、`spec-00003-FR-9`（离场）、
    `spec-00003-FR-10` 对共写适用。
  - `spec-00004`（桌面通知）的等待输入与结束通知对共写适用。
  - `whiteboard.config.yaml`：新增 agent 或启动形态进配置前，须先验证
    其在共写形态下的写域行为与授权交互（沿该文件注释对
    `spec-00001-AC-13.2` 的验证先例，扩展到共写；实现 plan 登记验证
    步骤）。
  - 代码 `SessionKind` 枚举扩展第五种，属实现 plan 的范围。

## 2. Stories

| Story | Value | Delivers |
| --- | --- | --- |
| S1 | 作为域主，我有一句想法，要与 agent 对话把它共写成一份 idea，而不是从空白模板手打首版 | spec-00006-FR-1, spec-00006-FR-2, spec-00006-FR-8 |
| S2 | 作为域主，我要给一些案例材料（贴文、仓外文件、URL），让 agent 写一份新 design，我 review 后再让它改 | spec-00006-FR-3, spec-00006-FR-4, spec-00006-FR-5, spec-00006-FR-7, rule-00001-BR-28 |
| S3 | 作为域主，我要修一份已有的 `draft` 或 `open`——包括此前没有任何 agent 通路的 decision、report、open plan 等——边看边说边改 | spec-00006-FR-1, spec-00006-FR-5, rule-00001-BR-29 |
| S4 | 作为文档负责人，我要共写的写入范围由系统保证、结束有单次 commit 可回溯、会话期间状态不被旁路，评审门照旧把关 | spec-00006-FR-6, spec-00006-FR-8, spec-00006-FR-9, spec-00006-FR-10, rule-00001-BR-30 |

## 3. Business Rules

| Rule set | Doc | Covers |
| --- | --- | --- |
| docs 工作流 | [rule-00001-docs-workflow](../rule/rule-00001-docs-workflow.md) | 共写的定义与材料口径（`rule-00001-BR-28`）、可发起的状态（`rule-00001-BR-29`）、落地写域（`rule-00001-BR-30`）、答疑与共写的分工（`rule-00001-BR-21`）、`reference` 的第二出生通路（`rule-00001-BR-26`） |

## 4. System Requirements

- **spec-00006-FR-1** (Event) 当用户对一份状态合法（`rule-00001-BR-29`）的
  文档发起共写时，系统应在内嵌终端启动交互式 agent 会话
  （`spec-00001-FR-11` 同一会话通道与终端），任务指令中给定：目标文档
  路径与类型、该类型文件夹的 README、目标类型的条目文法（有文法的类型，
  `spec-00001-FR-41` 口径）、写域约束（`rule-00001-BR-30`）与材料段
  （FR-3）；任务指令应要求外部材料中支撑结论的内容写进正文或落成
  `reference` 文档。异常节点不提供共写入口（`spec-00001-AC-2.4` 口径）。
- **spec-00006-FR-2** (Event) 当用户在顶栏新建中选择共写模式并给出类型与
  slug 时，系统应在确认时评估 `spec-00001-FR-53` 的三项拒绝（id 已存在、
  slug 不合小写连字符、类型不在 `entry`——任一命中即拒绝，不建档、不
  启动会话），通过后立即按该类型 `TEMPLATE.md` 建档并 commit（不经编辑器
  保存，是对 `spec-00001-FR-53`「保存即建档」的新分支），随即对新档发起
  FR-1 的共写会话；选择空白模式时保持 `spec-00001-FR-53` 原行为，不启动
  会话。
- **spec-00006-FR-3** (Event) 当用户发起共写（含 FR-2 的新建接续）时，
  系统应提供可选的材料输入——粘贴文本、仓内文档 id、仓库外绝对路径、
  URL——并将其拼入首条任务指令；会话运行中用户可在终端继续补充材料。
- **spec-00006-FR-4** (State-driven) 共写会话运行期间，系统应将目标文档的
  编辑器面板（右槽，`spec-00001-FR-31` 编辑器优先的既有语义；布局沿
  design-00002 §2：编辑器右侧、终端底部）与会话终端同屏呈现；agent 每次
  写入目标文档后，编辑器缓冲重载为盘上最新内容（`spec-00001-FR-42` 的
  共写例外）；会话运行且不在等待输入时，编辑器为只读。会话结束后编辑器
  恢复常规行为。
- **spec-00006-FR-5** (Complex) 当共写会话等待输入、且用户在编辑器修改并
  保存了目标文档时，系统应使保存照常生效（缓冲经 FR-4 的重载基于盘上
  最新版，不触发 `spec-00001-FR-5` 的冲突），并在用户的下一轮输入送往
  agent 时附带「用户已手改目标文档、动笔前须重读」的注记；用户保存后
  未再发下一轮即结束会话的，手改照常留在工作区并随 FR-8 收束。
- **spec-00006-FR-6** (Unwanted) 如果共写会话在仓库产生了目标文档与合式
  新建 `reference` 之外的变更，系统应在收束时将其滤除并复原，仅目标文档
  与合式 `reference` 进入 commit（`rule-00001-BR-30` 的执行层）。合式指：
  id 按 `rule-00001-BR-18` 相对收束时点取号、且不与运行中会话的保留号
  （`spec-00003-FR-1`）冲突，front matter 有效且 type 为 `reference`。
  目标文档 front matter 的 `id` 与 `status` 改动同属滤除之列，其正文写入
  照常落地。
- **spec-00006-FR-7** (Unwanted) 如果用户在终端拒绝了一次仓库外读取授权、
  或材料不可读（路径不存在、URL 获取失败），系统不应因此终止会话，其后
  轮次照常进行；授权与询问由所选 CLI 自身的权限机制承载，白板不代答、
  不预授权、不压掉询问。
- **spec-00006-FR-8** (Event) 当共写会话结束（自然退出或终止，终止的
  半成品承接沿 `spec-00001-FR-49` 口径、commit 不区分终止与自然结束）
  时，系统应将经 FR-6 过滤后的落地变更以一次 commit 收束（信息指明
  「共写」与目标文档 id；新建 `reference` 同 commit），并对目标文档与
  新建 `reference` 运行 front matter 与文法校验（`spec-00001-FR-17`
  口径）；无落地变更时不产生 commit。目标文档状态保持发起时的状态；
  收束时目标文件已不在盘上的，由既有异常与诊断体系承接
  （`spec-00001-FR-17`/`spec-00001-FR-40` 口径）。
- **spec-00006-FR-9** (Unwanted) 如果共写发起的目标状态不合法
  （`rule-00001-BR-29`：非 `draft` 的 living doc，或非 `draft`/`open` 的
  work item），系统应拒绝发起并说明原因；入口不按 status 条件化、照常
  呈现（`spec-00001-FR-9` 的既有先例）。
- **spec-00006-FR-10** (Unwanted) 如果目标文档存在运行中的共写会话，
  系统应拒绝对该文档的状态切换与评审动作并说明原因（与
  `spec-00003-FR-2` 的同文档会话互斥同向：会话期间目标不被旁路促进）；
  会话结束后照常。

**Acceptance (GWT)**

- **spec-00006-AC-1.1** (spec-00006-FR-1)
  Given 一份 `draft` 的 integration 文档
  When 对它发起共写
  Then 终端会话启动，任务指令含目标文档路径、integration 文件夹 README、
  写域约束与材料要求
- **spec-00006-AC-1.2** (spec-00006-FR-1)
  Given 一个异常节点
  When 查看其浮窗
  Then 不提供共写入口
- **spec-00006-AC-2.1** (spec-00006-FR-2)
  Given 顶栏新建选择 idea、共写模式、合式 slug
  When 确认
  Then 系统立即按 idea 模板建档并 commit，随即对新档启动共写会话
- **spec-00006-AC-2.2** (spec-00006-FR-2)
  Given 顶栏新建选择共写模式但 slug 不合小写连字符
  When 确认
  Then 新建被拒绝，不建档、不启动会话
- **spec-00006-AC-2.3** (spec-00006-FR-2)
  Given 顶栏新建选择共写模式，目标 id 已存在
  When 确认
  Then 新建被拒绝，不建档、不启动会话
- **spec-00006-AC-2.4** (spec-00006-FR-2)
  Given 顶栏新建选择共写模式，类型不在 `entry`
  When 确认
  Then 新建被拒绝，不建档、不启动会话
- **spec-00006-AC-2.5** (spec-00006-FR-2)
  Given 顶栏新建选择空白模式
  When 确认
  Then 保持既有行为：模板预填编辑器缓冲、保存才建档，不启动会话
- **spec-00006-AC-3.1** (spec-00006-FR-3)
  Given 发起共写时材料区给了一段粘贴文本与一个 URL
  When 会话启动
  Then 首条任务指令包含该文本与该 URL
- **spec-00006-AC-3.2** (spec-00006-FR-3)
  Given 发起共写时材料区给了一个仓内文档 id 与一个仓库外绝对路径
  When 会话启动
  Then 首条任务指令包含该 id 与该路径
- **spec-00006-AC-3.3** (spec-00006-FR-3)
  Given 发起共写时材料区留空
  When 会话启动
  Then 会话照常启动，任务指令不含材料段
- **spec-00006-AC-4.1** (spec-00006-FR-4)
  Given 对一份 `draft` 文档发起共写
  When 会话启动
  Then 目标文档的编辑器面板与会话终端同屏呈现
- **spec-00006-AC-4.2** (spec-00006-FR-4)
  Given 共写会话运行中
  When agent 写入目标文档
  Then 编辑器缓冲重载为盘上最新内容
- **spec-00006-AC-4.3** (spec-00006-FR-4)
  Given 共写会话运行且不在等待输入
  When 用户尝试编辑目标文档
  Then 编辑器为只读
- **spec-00006-AC-4.4** (spec-00006-FR-4)
  Given 共写会话结束
  When 用户编辑并保存目标文档
  Then 编辑器行为恢复常规（`spec-00001-FR-4`、`spec-00001-FR-5` 口径）
- **spec-00006-AC-5.1** (spec-00006-FR-5)
  Given 共写会话等待输入，用户在编辑器修改并保存了目标文档
  When 用户发送下一轮输入
  Then 保存已生效，且该轮送往 agent 的输入附带须重读文档的手改注记
- **spec-00006-AC-5.2** (spec-00006-FR-5)
  Given 共写会话等待输入，用户保存了手改后未再发下一轮
  When 用户结束会话
  Then 手改随收束 commit 照常落地
- **spec-00006-AC-6.1** (spec-00006-FR-6)
  Given 共写会话的产出改写了目标文档、又改写了另一份既有文档
  When 会话收束
  Then 越界改写被滤除并复原，目标文档的写入照常进 commit
- **spec-00006-AC-6.2** (spec-00006-FR-6)
  Given 共写会话的产出新建了一份非 `reference` 类型的文档
  When 会话收束
  Then 该新建被滤除
- **spec-00006-AC-6.3** (spec-00006-FR-6)
  Given 共写会话新建的 `reference` 的 id 与一个运行中会话的保留号冲突
  When 会话收束
  Then 该 `reference` 被滤除，域内其余写入照常进 commit
- **spec-00006-AC-6.4** (spec-00006-FR-6)
  Given 共写会话的产出把目标文档 front matter 的 `status` 改为 `active`
  When 会话收束
  Then 该改动被滤除，正文改动照常进 commit
- **spec-00006-AC-7.1** (spec-00006-FR-7)
  Given 共写会话中 agent 请求读取一个仓库外路径
  When 用户在终端拒绝授权
  Then 会话不终止，用户可继续下一轮对话
- **spec-00006-AC-7.2** (spec-00006-FR-7)
  Given 同 AC-7.1 后 agent 再次请求、用户再次拒绝
  When 拒绝落下
  Then 会话仍存活且可交互
- **spec-00006-AC-7.3** (spec-00006-FR-7)
  Given 材料中的 URL 获取失败
  When agent 报告失败
  Then 会话继续，其后轮次照常
- **spec-00006-AC-8.1** (spec-00006-FR-8)
  Given 共写会话有落地变更（目标文档与一份合式新建 `reference`）
  When 会话自然结束
  Then 产生一次 commit，信息指明「共写」与目标文档 id，两份文件同
  commit，校验运行，目标文档状态保持发起时状态
- **spec-00006-AC-8.2** (spec-00006-FR-8)
  Given 共写会话没有任何落地变更
  When 会话结束
  Then 不产生 commit
- **spec-00006-AC-8.3** (spec-00006-FR-8)
  Given 用户在 agent 写到一半时终止会话
  When 收束
  Then 过滤后的变更照常一次 commit（信息不区分终止与自然结束），半成品
  由既有异常与诊断体系承接
- **spec-00006-AC-8.4** (spec-00006-FR-8)
  Given 一个共写会话新建了两份合式 `reference`
  When 会话收束
  Then 两份均随该次 commit 落地
- **spec-00006-AC-9.1** (spec-00006-FR-9)
  Given 一份 `active` 的 spec 文档
  When 发起共写
  Then 发起被拒绝并说明原因，入口照常呈现
- **spec-00006-AC-9.2** (spec-00006-FR-9)
  Given 一份 `resolved` 的 task 文档
  When 发起共写
  Then 发起被拒绝并说明原因
- **spec-00006-AC-10.1** (spec-00006-FR-10)
  Given 目标文档存在运行中的共写会话
  When 用户对该文档执行接收
  Then 动作被拒绝并说明原因
- **spec-00006-AC-10.2** (spec-00006-FR-10)
  Given 目标文档的共写会话已结束
  When 用户对该文档执行接收
  Then 按既有评审门照常评估

## 5. Technical Design

| Design | Doc | Covers |
| --- | --- | --- |
| 白板服务端 | [design-00001-docs-whiteboard](../design/design-00001-docs-whiteboard.md)（修订轮登记） | `SessionKind` 第五种、共写任务指令载荷、收束过滤与校验挂点、新 agent 启动形态的验证步骤 |
| 白板 UI | [design-00002-whiteboard-ui](../design/design-00002-whiteboard-ui.md)（修订轮登记） | 工作区布局（编辑器右槽 + 终端底部）、编辑器只读态与缓冲重载、新建对话框的模式选择、浮窗共写入口 |

## 6. Out of Scope

- 白板自建抓取或代理承载 URL 材料（抓取与授权能力属所选 CLI，
  `decision-00015` §3）。
- 编辑器内嵌 AI 补全（无会话形态，`decision-00015` §3）。
- 对 `active` 文档的直达共写（永远先经修订轮，`rule-00001-BR-29`）。
- 材料的持久附件存储与规模配额（材料价值经正文与 `reference` 沉淀，
  `rule-00001-BR-28`；配额沿 `spec-00005` §6 的先例显式排除）。
- agent 每轮写入的差异标示与修订轮版本 diff 呈现（后续增强；后者沿
  `spec-00001` §6 的既有排除，「上一有效版」由 git 历史承载）。

## 7. Open Questions

无——本轮裁决闭合：record 证据约定写入其 README 而不排除共写、共写
及于 `draft` 与 `open` work item、Open Questions 条目可随对话结论关闭
（促进门仍由人把关）、写域执行层取白板收束过滤，四项经域主逐条确认
（在案于 `decision-00015`）；新建 `reference` 的 id 合法性并入收束过滤
的「合式」判定（FR-6），不另设分配机制。

## Links

- Parent: prd-00001-docs-whiteboard
- Rules: rule-00001-docs-workflow（BR-28 … BR-30、BR-21、BR-26）
- Decision: decision-00015-whiteboard-co-write
- Design: design-00001-docs-whiteboard、design-00002-whiteboard-ui
  （修订轮待登记，见 §5）
- Siblings: spec-00001-docs-whiteboard、spec-00003-whiteboard-parallel-sessions、
  spec-00004-whiteboard-desktop-notifications（交接见 §1）
