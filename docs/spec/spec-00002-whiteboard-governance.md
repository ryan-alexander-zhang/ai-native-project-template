---
id: spec-00002-whiteboard-governance
type: spec
status: active
parent: prd-00001-docs-whiteboard
---

# Spec: 白板治理增强

> 把 `rule-00001` 里已经写死、白板却没有守住的四道关补上——促进出 `draft` 的
> Open Questions 门、归档的 `supersedes` 配对门、关系字段与类型的配对、撞 id
> 的呈现与寻址——并给全局的覆盖率、异常与诊断各开一个可下钻的入口。

## 1. Context

- canonical terms 见 `CONTEXT.md`：白板、节点、关系边、异常、解析诊断、
  促进、促进门、归档门、状态流转、动作被拒、需求条目、覆盖状态、
  全局覆盖率视图、关系矩阵、撞 id、异常清单、诊断清单、检视面板、
  流程配置、评审动作、接收、答疑、推进、命令面板、呈现状态。
- 本 spec 的 Markdown 方言取 GFM。
- 输入：`parent` 为 [prd-00001-docs-whiteboard](../prd/prd-00001-docs-whiteboard.md)。
- 本 spec 沿用 `spec-00001-docs-whiteboard` 对「文档」的收窄：`docs/**/*.md`
  中带 id front matter 的文件，不含各文件夹的 `README.md` 与 `TEMPLATE.md`。
- 本 spec 是 `spec-00001-docs-whiteboard` 的**并列新 spec**（`docs/spec/README.md`
  的 Sizing and Splitting 第 1 条：新能力开新 spec，不向 `active` spec 追加
  条目），与它同 `parent`，不 supersede 它。
- 本 spec **覆盖** `spec-00001` §6 的两条范围外事项——「id 唯一性校验」与
  「`active → archived` 的归档配对自动化」——但**不就地删除它们**：把这两条从
  `spec-00001` §6 移除属于 `spec-00001` 自己的修订轮，由 plan 轮列为一项任务。
- **与 issue-00004 §6 拟定方向的偏离（已记录）**：该 issue 提议把撞 id 纳入
  `spec-00001-FR-2` 的异常清单，即扩写 `spec-00001`；本轮改为开并列新 spec，
  依据是 `docs/spec/README.md` 新增的 Sizing and Splitting 第 1 条。异常语义
  本身不变（FR-8 仍按 `spec-00001-FR-2` 的方式标记），变的只是承载它的文档。
  `docs/README.md` 至今没有一句把「id 唯一」写成约束（issue-00004 §8 的
  doc verdict），补写它同样由 plan 轮列为一项任务。
- issue-00004 的 `blocks` 现同时列出本 spec：它在 `spec-00001` 之外也挡住本
  spec 的实现，FR-8 与 FR-9 落地并验证后它才可 `resolved`。
- 已确认的读数（本轮核对全仓）：各文件夹 README 中，`idea/` 与 `prompt/` 没有
  「Relations」小节，即这两型的允许字段集合为空；其余类型的现有文档均未声明
  其 README 未列出的关系字段——本轮引入 FR-5 的矩阵不会立刻产出既有诊断。
- 「促进出 `draft`」在本 spec 中指状态流转的目标为该文档种类的促进态：
  living doc 的 `active`、work item 的 `open`（`rule-00001-BR-10` 的两个目标）。

## 2. Stories

| Story | Value | Delivers |
| --- | --- | --- |
| S1 | 作为文档负责人，我要促进与归档这两道 `rule-00001` 已写死的门在**每条通路上**都拦得住，这样把关不再取决于我从哪个入口点下去 | spec-00002-FR-1, spec-00002-FR-2, spec-00002-FR-3, spec-00002-FR-4 |
| S2 | 作为文档负责人，我要「哪种类型能带哪些关系字段」由配置承载并当场提示，这样各文件夹 README 的散文约定不再只靠人记 | spec-00002-FR-5, spec-00002-FR-6, spec-00002-FR-7 |
| S3 | 作为文档负责人，我要两份文档撞 id 时两份都看得见、都被标红、都不被当作证据，且能按文件路径把它改回来，这样不会有一份文档在白板上凭空消失、动作落到另一份身上 | spec-00002-FR-8, spec-00002-FR-9 |
| S4 | 作为文档负责人，我要覆盖缺口、异常与解析诊断在顶栏就能全局看到并一键跳到出问题的那份文档，这样排查不必逐个节点点过去 | spec-00002-FR-10, spec-00002-FR-11, spec-00002-FR-12, spec-00002-FR-13, spec-00002-FR-14, spec-00002-FR-15 |

## 3. Business Rules

| Rule set | Doc | Covers |
| --- | --- | --- |
| docs 工作流 | [rule-00001-docs-workflow](../rule/rule-00001-docs-workflow.md) | 未决 Open Questions 阻断促进（`rule-00001-BR-12`）、归档须有 `supersedes` 配对（`rule-00001-BR-19`）、状态流转决策表（`rule-00001-BR-2` … `rule-00001-BR-9`，本 spec 的两道门加在它之后） |

本 spec 的两道门都不新立规则，只补上承载：`rule-00001-BR-12` 此前只在接收
通路（`spec-00001-FR-8`）上生效，`rule-00001-BR-19` 此前完全没有承载
（`spec-00001` §6 明列为范围外）。

关系矩阵（FR-5）承载的是**各文件夹 README 的「Relations」小节**所写的
「该类型带哪些关系字段」，外加 `docs/README.md` 的「`parent` 单值」一条。
`docs/README.md` 的 Relations 表是**字段释义**（每个字段各是什么意思），不是
类型-字段配对，不在矩阵的承载范围内。这些约定今天都不由任何 `rule` 文档持有；
本轮的裁定是：矩阵只承载既有的文件夹 README 约定，**把它们提为 `rule-00001`
的 BR 一事本轮不做**，需要时另行处置。

## 4. System Requirements

- **spec-00002-FR-1** (Unwanted) 若状态变更请求把一份 `draft` 文档促进出
  `draft`（living doc 促为 `active`、work item 促为 `open`）而该文档带未决
  Open Questions，系统应拒绝该请求且不修改文件，per `rule-00001-BR-12`；
  「未决」的判定与接收通路（`spec-00001-FR-8`）同一判定，两条通路不得给出
  不同结论，拒绝消息应点名未决 Open Questions 这道门（与 FR-3 的拒绝消息
  对称）。本条是缺陷修复——`rule-00001-BR-12` 此前可经状态变更通路绕过，见
  [issue-00015-open-questions-gate-bypassed-on-status-path](../issue/issue-00015-open-questions-gate-bypassed-on-status-path.md)。
- **spec-00002-FR-2** (Ubiquitous) 促进门只作用于**促进出 `draft`** 的流转：
  `draft → archived`、work item 的 `draft → wontfix` 与 `open → resolved`、
  以及 living doc 的 `active → draft`（修订轮）都不经本门，仍由
  `spec-00001-FR-6`/`spec-00001-FR-7`（合法性）、FR-3（归档门）与
  `spec-00001-FR-52`（resolved 门）各自持有。已促进的文档新增未决问题时
  **不回退**（`rule-00001-BR-12`），本门只在其后续促进时把关。
- **spec-00002-FR-3** (Unwanted) 若状态变更请求的目标状态为 `archived`，而
  仓库中不存在 front matter `supersedes` 列出该文档 id 的文档，系统应拒绝该
  请求且不修改文件，per `rule-00001-BR-19`；拒绝消息应说明缺少列出该 id 的
  `supersedes` 配对。本门适用于**一切文档类型与一切来源状态**的 →`archived`
  流转。其中一条后果是明知并接受的：`wontfix` 与 `resolved` 的 work item 在
  没有替代文档之前**永远到不了 `archived`**——这正是 `rule-00001-BR-19` 的
  本意（`archived` 意为「被替代」，work item 的完成态是 `resolved`/`wontfix`）。
- **spec-00002-FR-4** (Ubiquitous) 归档门的配对判定以全仓库文档 front matter
  的 `supersedes` 声明为准：存在**另一份**文档的 `supersedes` 列出该 id 即
  成立，不区分该替代文档的类型、status（`draft` 与 `archived` 的替代文档同样
  成立）与**节点健康**（替代文档自身是异常节点时其声明照样算数——配对读的是
  front matter 的声明，不是节点是否健康）；文档自身的 `supersedes` 不构成对
  自己的配对。本门只把关流转，不回溯——仓库中既有的、无配对的 `archived`
  文档不因本门转异常，也不被提示（见 §6）。
- **spec-00002-FR-5** (Ubiquitous) 流程配置应以机器可读的**关系矩阵**（类型 →
  允许的关系字段）承载各文件夹 README 「Relations」小节的既有约定，外加
  `docs/README.md` 的「`parent` 单值」一条（如同 `entry` 承载
  `rule-00001-BR-26`）；该矩阵是「某关系字段是否属于某类型」的唯一判定依据，
  白板不解析散文。某类型的允许字段集合为**空**表示该类型不带任何关系字段
  （`idea` 与 `prompt` 即此情形）；某类型**不出现在矩阵中**则不对它做该校验
  ——逐类型选择加入，与矩阵整体缺失时的行为一致。
- **spec-00002-FR-6** (Unwanted) 关系矩阵应进入 `spec-00001-FR-15` 的同款启动
  校验：矩阵引用未在 `types` 中声明的类型、为某类型列出未在 `relations` 中
  声明的字段、或某类型的值不是字符串列表时，系统应拒绝启动并给出指明该类型
  或该字段的错误信息。**矩阵缺失或为空时不做字段-类型校验，系统照常启动**
  ——这是明知的取舍：既有配置文件不带该矩阵，向后兼容优先于「配置必须完备」。
- **spec-00002-FR-7** (Unwanted) 若一份文档声明了其 `type` 在矩阵中不被允许的
  关系字段，或把 `parent` 声明为多值，系统应产出一条 `relation-field` 类的
  **解析诊断**（含来源文档 id、字段名与该类型），计入 `spec-00001-FR-40` 的
  诊断计数并在诊断清单（FR-14）中列出；该文档的 `spec-00001-FR-2` 异常判定
  **不因此改变**，其边、覆盖推导与一切下游判定照常，无任何连锁。取舍：字段
  写法漂移是**写法**问题、不是**损坏**，与 `spec-00001-FR-40` 的解析诊断同族
  ——诊断不阻塞任何功能，而节点异常会剥夺该节点的全部动作。
- **spec-00002-FR-8** (Unwanted) 若两份或以上文档解析出同一 id，系统应把
  **每一份**都呈现为一个节点、以**文件路径**为节点键（与 `spec-00001-FR-2`
  对无 id 节点的处置同一处置），标签含文件路径与撞的 id，并各自标记为异常，
  每个节点的 problem 点名其余同 id 文档的文件路径；不得只呈现其中一份。
  命令面板（`spec-00001-FR-26`）应能按文件路径、也能按撞的 id 文本检索到
  它们。关系字段**指向该撞的 id** 的边判为无法解析（目标歧义，按
  `spec-00001-FR-2` 的断链边处置）。呈现状态（`spec-00001-FR-44`）对这些节点
  按文件路径保持。撞 id 的文档**不参与需求条目归属、覆盖推导与 resolved 门**
  ——其正文中的条目不被任何一处认领，落到撞 id 文档上的交付范围 id 一律计为
  **无法解析的缺口**（宁缺勿错：歧义的证据不算证据）。撞 id 消解后——多余的
  文档被删除，或其中一份改了 id——余下的节点在刷新后不再因撞 id 标记异常。
- **spec-00002-FR-9** (Unwanted) 撞 id 的节点是异常节点，故其浮窗内容与动作
  拒绝（状态切换、评审含澄清与审计、答疑、推进）已由 `spec-00001-FR-2` 持有，
  本条不重述。本条只增两件：（a）若一次写入按**撞的 id** 寻址，系统应拒绝该
  写入、给出「先修复 id 冲突」的消息，且不修改任何文件；（b）编辑按**文件
  路径**寻址——异常节点的编辑入口寻址的是该节点自己的文件——按路径寻址的
  编辑保存照常落盘（冲突判定仍按 `spec-00001-FR-5`），这**就是**撞 id 的
  修复通路。
- **spec-00002-FR-10** (Event) 当用户经顶栏入口打开**全局覆盖率视图**时，
  系统应以全屏对话框（与命令面板同一承载形态）呈现它，**不占用右侧槽位**，
  且不受编辑器、内嵌终端、子画布或会话运行状态限制——任何时候都能打开；按
  Esc 或点击关闭控件即关闭。视图应按文档列出仓库中每份 spec 与 rule 的需求
  条目覆盖三态计数（已验证 / 未通过 / 未覆盖），推导复用
  `spec-00001-FR-32` 的同一判定、证据集为全部 record 文档。列入范围：
  **不区分文档 status**（`draft`/`active`/`archived` 一律在列，与证据侧不区分
  record status 同一口径），front matter 异常但正文可解析的文档同样在列
  （与 `spec-00001-FR-31` 对齐）；撞 id 的文档**不在列**（FR-8：其条目不参与
  推导）。仓库中没有任何可列文档时呈现空态。计数随刷新更新
  （`spec-00001-FR-42` 的同一通路）。
- **spec-00002-FR-11** (Event) 当用户展开全局覆盖率视图中的某一份文档时，
  系统应逐条列出该文档的每个需求条目 id 及其覆盖状态；无需求条目的文档呈现
  空态。同一时刻至多一行处于展开态——展开 B 即收起 A（与
  `spec-00001-FR-38` 同构）；再次点击该行收起；展开态跨刷新按文档 id 保持
  （并入 `spec-00001-FR-44` 的呈现状态族）。
- **spec-00002-FR-12** (Event) 当用户点击全局覆盖率视图中的某个需求条目时，
  系统应关闭该视图、在顶层白板定位并选中该条目所属的文档节点；检视面板则按
  `spec-00001-FR-31` 的既有右槽规则出现——编辑器占用右槽时检视面板不出现，
  但定位与选中照常发生。目标文档在刷新后已不存在时，系统应以提示条拒绝该
  动作，不改变当前选中。
- **spec-00002-FR-13** (Event) 当用户点击顶栏的**异常计数**时，系统应以与
  FR-10 同一形态的对话框列出**异常清单**：每条含来源（文件路径，或该文件已
  解析出文档 id 时含文档 id）与 problem 文本；按 Esc 或点击关闭控件即关闭。
  关系边的异常归属**声明方**文档——断链是在声明方修的——其来源即声明方。
  计数为零时该计数呈现现状文案且不可点击。
- **spec-00002-FR-14** (Event) 当用户点击顶栏的**诊断计数**时，系统应以同一
  形态的对话框列出**诊断清单**：每条含来源文档 id、诊断类别（含 FR-7 新增的
  `relation-field`）与原文行，取 `spec-00001-FR-40` 的同一诊断集。两个计数
  各自独立，两份清单的内容不混。计数为零时该计数呈现现状文案且不可点击。
- **spec-00002-FR-15** (Event) 当用户点击异常清单或诊断清单中的一条时，系统应
  在顶层白板定位并选中其对应的节点。**不存在无法定位的条目**：front matter
  整体不可解析的文件同样有节点（以文件路径为键与标签，`spec-00001-FR-2`），
  关系边的异常定位到其声明方节点。

- **spec-00002-AC-1.1** (spec-00002-FR-1)
  Given 一份带未决 Open Questions 的 `draft` spec
  When 经状态变更请求把它改为 `active`
  Then 请求被拒绝，文件未被修改
- **spec-00002-AC-1.2** (spec-00002-FR-1)
  Given 一份带未决 Open Questions 的 `draft` plan
  When 经状态变更请求把它改为 `open`
  Then 请求被拒绝，文件未被修改
- **spec-00002-AC-1.3** (spec-00002-FR-1)
  Given 同 AC-1.1 的文档
  When 促进请求被拒绝
  Then 拒绝消息点名该文档有未决 Open Questions
- **spec-00002-AC-1.4** (spec-00002-FR-1)
  Given 同 AC-1.1 的文档，其第一次请求已被拒绝
  When 再次发出同一请求
  Then 仍被拒绝，文件仍未被修改
- **spec-00002-AC-1.5** (spec-00002-FR-1)
  Given 一份不含 Open Questions 小节的 `draft` design
  When 经状态变更请求把它改为 `active`
  Then 流转成功
- **spec-00002-AC-1.6** (spec-00002-FR-1)
  Given 一份 `draft` prd 有 Open Questions 小节，但小节内没有任何列表项
  When 经状态变更请求把它改为 `active`
  Then 流转成功
- **spec-00002-AC-1.7** (spec-00002-FR-1)
  Given 一份带未决 Open Questions 的 `draft` prd，对它的接收已被拒绝
  When 改经状态变更请求把它促进为 `active`
  Then 同样被拒绝
- **spec-00002-AC-2.1** (spec-00002-FR-2)
  Given 一份带未决 Open Questions 的 `draft` issue
  When 经状态变更请求把它改为 `wontfix`
  Then 流转成功
- **spec-00002-AC-2.2** (spec-00002-FR-2)
  Given 一份带未决 Open Questions 的 `active` spec
  When 经状态变更请求把它改为 `draft`（修订轮）
  Then 流转成功
- **spec-00002-AC-2.3** (spec-00002-FR-2)
  Given 一份带未决 Open Questions 的 `draft` design，且存在一份
  `supersedes` 列出它的文档
  When 经状态变更请求把它改为 `archived`
  Then 不经本门，流转成功
- **spec-00002-AC-2.4** (spec-00002-FR-2)
  Given 一份带未决 Open Questions、交付范围已全部验证的 `open` plan
  When 经状态变更请求把它改为 `resolved`
  Then 不经本门，流转成功
- **spec-00002-AC-2.5** (spec-00002-FR-2)
  Given 一份 `active` 的 spec 在白板之外被加进了未决 Open Questions
  When 白板刷新
  Then 该文档仍为 `active`，不被回退到 `draft`
- **spec-00002-AC-3.1** (spec-00002-FR-3)
  Given 一份 `active` 的 spec，仓库中没有任何文档的 `supersedes` 列出它
  When 经状态变更请求把它改为 `archived`
  Then 请求被拒绝，文件未被修改
- **spec-00002-AC-3.2** (spec-00002-FR-3)
  Given 同 AC-3.1 的文档
  When 归档请求被拒绝
  Then 拒绝消息说明缺少列出该 id 的 `supersedes` 配对
- **spec-00002-AC-3.3** (spec-00002-FR-3)
  Given 一份 `resolved` 的 plan，仓库中没有任何文档 `supersedes` 它
  When 经状态变更请求把它改为 `archived`
  Then 请求被拒绝
- **spec-00002-AC-3.4** (spec-00002-FR-3)
  Given 一份 `wontfix` 的 issue，仓库中没有配对文档，其归档已被拒绝一次
  When 再次请求归档
  Then 仍被拒绝，文件仍未被修改
- **spec-00002-AC-4.1** (spec-00002-FR-4)
  Given 文档 B 的 `supersedes` 列出文档 A 的 id，且 B 的 status 为 `draft`
  When 把 A 改为 `archived`
  Then 流转成功
- **spec-00002-AC-4.2** (spec-00002-FR-4)
  Given 文档 B 的 `supersedes` 列出文档 A 的 id，且 B 与 A 类型不同
  When 把 A 改为 `archived`
  Then 流转成功
- **spec-00002-AC-4.3** (spec-00002-FR-4)
  Given 文档 A 的 `supersedes` 列出它自己的 id，仓库中再无其他文档列出它
  When 把 A 改为 `archived`
  Then 归档被拒绝
- **spec-00002-AC-4.4** (spec-00002-FR-4)
  Given 文档 B 与文档 C 的 `supersedes` 都列出文档 A 的 id
  When 把 A 改为 `archived`
  Then 流转成功
- **spec-00002-AC-4.5** (spec-00002-FR-4)
  Given 文档 B 的 `supersedes` 列出 A 与另外两份文档的 id
  When 把 A 改为 `archived`
  Then 流转成功
- **spec-00002-AC-4.6** (spec-00002-FR-4)
  Given 文档 B 的 `supersedes` 列出 A，而 B 自身因 front matter 非法被标记
  为异常节点
  When 把 A 改为 `archived`
  Then 流转成功
- **spec-00002-AC-5.1** (spec-00002-FR-5)
  Given 关系矩阵为 `design` 允许 `informs`
  When 一份 design 文档声明 `informs`
  Then 该字段判为允许，不产出诊断
- **spec-00002-AC-5.2** (spec-00002-FR-5)
  Given 关系矩阵为 `record` 列出的允许字段不含 `implements`
  When 一份 record 文档声明 `implements`
  Then 该字段判为不允许
- **spec-00002-AC-5.3** (spec-00002-FR-5)
  Given 关系矩阵为 `idea` 声明空的允许字段集合
  When 一份 idea 文档声明 `motivated_by`
  Then 产出一条该字段的诊断
- **spec-00002-AC-5.4** (spec-00002-FR-5)
  Given 关系矩阵中没有 `operation` 这一项
  When 一份 operation 文档声明 `implements`
  Then 不对它做该校验，不产出诊断
- **spec-00002-AC-6.1** (spec-00002-FR-6)
  Given 关系矩阵为一个未在 `types` 中声明的类型列出字段
  When 启动白板服务
  Then 启动失败，错误信息指明该类型
- **spec-00002-AC-6.2** (spec-00002-FR-6)
  Given 关系矩阵为某个已声明类型列出一个未在 `relations` 中声明的字段
  When 启动白板服务
  Then 启动失败，错误信息指明该字段
- **spec-00002-AC-6.3** (spec-00002-FR-6)
  Given 关系矩阵中某类型的值是一个字符串而不是字符串列表
  When 启动白板服务
  Then 启动失败，错误信息指明该类型
- **spec-00002-AC-6.4** (spec-00002-FR-6)
  Given 流程配置不含关系矩阵，且仓库中存在字段与类型不配对的文档
  When 启动白板服务并加载白板
  Then 正常启动，且不产出任何 `relation-field` 诊断
- **spec-00002-AC-7.1** (spec-00002-FR-7)
  Given 关系矩阵不为 `record` 允许 `implements`，一份 record 声明了它
  When 加载白板
  Then 产出一条 `relation-field` 诊断，含该 record 的 id、字段名与类型
- **spec-00002-AC-7.2** (spec-00002-FR-7)
  Given 同 AC-7.1 的仓库
  When 查看该 record 节点
  Then 它不是异常节点，其边与覆盖推导照常
- **spec-00002-AC-7.3** (spec-00002-FR-7)
  Given 一份文档的 `parent` 声明为含两个 id 的列表
  When 加载白板
  Then 产出一条 `relation-field` 诊断，点名 `parent` 为单值字段
- **spec-00002-AC-7.4** (spec-00002-FR-7)
  Given 同 AC-7.1 的仓库，此前诊断计数为零
  When 查看顶栏
  Then 诊断计数为 1
- **spec-00002-AC-8.1** (spec-00002-FR-8)
  Given 两份不同路径的文档解析出同一 id
  When 加载白板
  Then 两个节点都呈现，各以自己的文件路径为键，标签含该路径与撞的 id
- **spec-00002-AC-8.2** (spec-00002-FR-8)
  Given 同 AC-8.1 的仓库
  When 查看这两个节点
  Then 两者都标记为异常，各自的 problem 点名对方的文件路径
- **spec-00002-AC-8.3** (spec-00002-FR-8)
  Given 三份不同路径的文档解析出同一 id
  When 加载白板
  Then 三个节点都呈现且都标记为异常
- **spec-00002-AC-8.4** (spec-00002-FR-8)
  Given 同 AC-8.1 的仓库
  When 在命令面板中输入撞的那个 id
  Then 两个节点都出现在结果中，各自可分别定位
- **spec-00002-AC-8.5** (spec-00002-FR-8)
  Given 同 AC-8.1 的仓库
  When 在命令面板中输入其中一份的文件路径片段
  Then 只列出该路径对应的那一个节点
- **spec-00002-AC-8.6** (spec-00002-FR-8)
  Given 第三份文档的 `parent` 指向那个撞的 id
  When 加载白板
  Then 该边判为无法解析，按断链边呈现
- **spec-00002-AC-8.7** (spec-00002-FR-8)
  Given 撞 id 的两份文档中有一份是带需求条目的 spec
  When 打开全局覆盖率视图
  Then 该文档不在列
- **spec-00002-AC-8.8** (spec-00002-FR-8)
  Given 一个 `open` plan 的交付范围含撞 id 那份 spec 的一个条目 id
  When 促进为 `resolved`
  Then 流转被拒绝，该 id 计为无法解析的缺口
- **spec-00002-AC-8.9** (spec-00002-FR-8)
  Given 撞 id 的两个节点之一处于选中态
  When 白板刷新
  Then 选中仍落在同一文件路径的那个节点上
- **spec-00002-AC-8.10** (spec-00002-FR-8)
  Given 同 AC-8.1 的两份文档，其中一份在白板之外被删除
  When 白板刷新
  Then 余下的节点不再因撞 id 标记为异常
- **spec-00002-AC-8.11** (spec-00002-FR-8)
  Given 同 AC-8.1 的两份文档，其中一份的 id 被改为一个未被占用的 id
  When 白板刷新
  Then 两个节点都不再因撞 id 标记为异常
- **spec-00002-AC-9.1** (spec-00002-FR-9)
  Given 一份撞 id 的 `draft` spec
  When 点击它的节点
  Then 浮窗按 `spec-00001-FR-2` 的异常节点处置，只提供编辑入口
- **spec-00002-AC-9.2** (spec-00002-FR-9)
  Given 同 AC-9.1 的文档
  When 经按撞的 id 寻址的写入请求写它
  Then 请求被拒绝、消息提示先修复 id 冲突，无文件被修改
- **spec-00002-AC-9.3** (spec-00002-FR-9)
  Given 同 AC-9.1 的文档，其第一次 id 寻址写入已被拒绝
  When 再次发出同一请求
  Then 仍被拒绝，仍无文件被修改
- **spec-00002-AC-9.4** (spec-00002-FR-9)
  Given 同 AC-9.1 的文档，用户从它的节点打开编辑器并改掉了 id
  When 保存
  Then 保存成功，只写入该节点对应路径的那一个文件
- **spec-00002-AC-10.1** (spec-00002-FR-10)
  Given 仓库中有两份 spec 与一份 rule
  When 经顶栏入口打开全局覆盖率视图
  Then 视图按文档列出这三份，每份各带已验证、未通过、未覆盖三个计数
- **spec-00002-AC-10.2** (spec-00002-FR-10)
  Given 某份 spec 有五个需求条目，其中两个的覆盖状态为未覆盖
  When 打开全局覆盖率视图
  Then 该文档行的未覆盖计数为 2
- **spec-00002-AC-10.3** (spec-00002-FR-10)
  Given 仓库中没有任何 spec 或 rule 文档
  When 打开全局覆盖率视图
  Then 呈现空态，不出错
- **spec-00002-AC-10.4** (spec-00002-FR-10)
  Given 全局覆盖率视图已打开，某 record 在白板之外新增了一条通过的验收行
  When 白板刷新
  Then 对应文档行的计数随之更新
- **spec-00002-AC-10.5** (spec-00002-FR-10)
  Given 编辑器正占用右侧槽位且有未保存的缓冲区
  When 打开全局覆盖率视图
  Then 视图照常打开、不占用右侧槽位，编辑器缓冲区不受影响
- **spec-00002-AC-10.6** (spec-00002-FR-10)
  Given 白板正处于某份文档的子画布中
  When 打开全局覆盖率视图
  Then 视图照常打开
- **spec-00002-AC-10.7** (spec-00002-FR-10)
  Given 全局覆盖率视图已打开
  When 按 Esc
  Then 视图关闭
- **spec-00002-AC-10.8** (spec-00002-FR-10)
  Given 全局覆盖率视图已打开
  When 点击关闭控件
  Then 视图关闭
- **spec-00002-AC-10.9** (spec-00002-FR-10)
  Given 仓库中有一份 `archived` 的 spec 与一份 `draft` 的 rule
  When 打开全局覆盖率视图
  Then 两份都在列
- **spec-00002-AC-10.10** (spec-00002-FR-10)
  Given 一份 spec 的 front matter 非法但正文可解析
  When 打开全局覆盖率视图
  Then 它在列，计数照常推导
- **spec-00002-AC-11.1** (spec-00002-FR-11)
  Given 全局覆盖率视图已打开
  When 展开其中一份 spec
  Then 列出该 spec 的每个需求条目 id 及其覆盖状态
- **spec-00002-AC-11.2** (spec-00002-FR-11)
  Given 列表中有一份不含任何需求条目的 rule
  When 展开它
  Then 呈现无条目的空态
- **spec-00002-AC-11.3** (spec-00002-FR-11)
  Given 某份文档已展开
  When 再次点击该行
  Then 该行收起，只剩计数
- **spec-00002-AC-11.4** (spec-00002-FR-11)
  Given 文档 A 的行已展开
  When 展开文档 B 的行
  Then A 收起，只有 B 处于展开态
- **spec-00002-AC-11.5** (spec-00002-FR-11)
  Given 某份文档的行处于展开态
  When 白板刷新
  Then 该行仍处于展开态
- **spec-00002-AC-12.1** (spec-00002-FR-12)
  Given 全局覆盖率视图中某份 spec 已展开
  When 点击其中一条需求条目
  Then 顶层白板定位并选中该 spec 节点，其检视面板呈现
- **spec-00002-AC-12.2** (spec-00002-FR-12)
  Given 同 AC-12.1，且编辑器正占用右侧槽位
  When 点击该需求条目
  Then 定位与选中照常发生，检视面板不出现
- **spec-00002-AC-12.3** (spec-00002-FR-12)
  Given 同 AC-12.1
  When 点击该需求条目
  Then 全局覆盖率视图关闭
- **spec-00002-AC-12.4** (spec-00002-FR-12)
  Given 白板正处于某份文档的子画布中，全局覆盖率视图已打开
  When 点击另一份文档的需求条目
  Then 白板退回顶层并选中该另一份文档的节点
- **spec-00002-AC-12.5** (spec-00002-FR-12)
  Given 全局覆盖率视图已打开，其中一份文档在白板之外被删除且白板已刷新
  When 点击该文档的一条需求条目
  Then 动作被拒并以提示条说明，当前选中不变
- **spec-00002-AC-13.1** (spec-00002-FR-13)
  Given 顶栏异常计数为 3
  When 点击该计数
  Then 列出 3 条异常，每条含来源与 problem 文本
- **spec-00002-AC-13.2** (spec-00002-FR-13)
  Given 顶栏异常计数为零
  When 查看顶栏
  Then 该计数呈现现状文案且不可点击
- **spec-00002-AC-13.3** (spec-00002-FR-13)
  Given 异常清单已打开
  When 按 Esc
  Then 清单关闭
- **spec-00002-AC-13.4** (spec-00002-FR-13)
  Given 文档 A 的 `implements` 指向一个不存在的 id
  When 打开异常清单
  Then 该条的来源是 A（声明方），不是被指向的那个 id
- **spec-00002-AC-14.1** (spec-00002-FR-14)
  Given 顶栏诊断计数为 2
  When 点击该计数
  Then 列出 2 条诊断，每条含来源文档 id、类别与原文行
- **spec-00002-AC-14.2** (spec-00002-FR-14)
  Given 顶栏诊断计数为零
  When 查看顶栏
  Then 该计数呈现现状文案且不可点击
- **spec-00002-AC-14.3** (spec-00002-FR-14)
  Given 同一份 spec 既是异常节点、又带一条解析诊断
  When 打开诊断清单
  Then 清单只列出那条诊断，该节点的异常不混入
- **spec-00002-AC-14.4** (spec-00002-FR-14)
  Given 仓库中存在一条 FR-7 产出的 `relation-field` 诊断
  When 打开诊断清单
  Then 该条在列，类别呈现为 `relation-field`
- **spec-00002-AC-15.1** (spec-00002-FR-15)
  Given 异常清单中的一条来自一份 front matter 非法但有 id 的文档
  When 点击该条
  Then 白板定位并选中该文档的节点
- **spec-00002-AC-15.2** (spec-00002-FR-15)
  Given 诊断清单中的一条来自某份 spec
  When 点击该条
  Then 白板定位并选中该 spec 节点
- **spec-00002-AC-15.3** (spec-00002-FR-15)
  Given 异常清单中的一条来自一份 front matter 整体不可解析的文件
  When 点击该条
  Then 白板定位并选中该文件以文件路径为标签的节点
- **spec-00002-AC-15.4** (spec-00002-FR-15)
  Given 异常清单中的一条是文档 A 声明的一条断链关系边
  When 点击该条
  Then 白板定位并选中 A 的节点

## 5. Technical Design

| Design | Doc | Covers |
| --- | --- | --- |
| Docs 白板 MVP | [design-00001-docs-whiteboard](../design/design-00001-docs-whiteboard.md) | 现行的流程配置契约（§3）、API 契约（§7）、图构建与覆盖推导——本 spec 的两道门、关系矩阵与撞 id 判定都落在这些既有结构上 |
| Docs 白板界面 | [design-00002-whiteboard-ui](../design/design-00002-whiteboard-ui.md) | 现行的设计令牌、顶栏与右侧槽位的占用规则、图标语言、可访问性 |

上表两份 design 的修订由 plan 轮产出并回链本 spec：design-00001 §3（关系矩阵
进流程配置契约与启动校验）与 §7（覆盖率、异常、诊断三个下钻载荷、按路径寻址
的节点键），design-00002 的顶栏入口与全屏对话框形态。

## 6. Out of Scope

- `informs` / `constrains` / `blocks` 的**方向语义**执法——各文件夹 README
  规定这三个字段声明在上游文档上，本 spec 只校验「该类型能否带该字段」，
  不判定边指向的对错。
- **未知关系字段的检测**——拼错的字段名（如 `informss`）与 `relations` 之外的
  任意 front matter 键一律不进 FR-7 的诊断：矩阵只回答「已知字段属不属于这个
  类型」，不回答「这个键是不是关系字段」。
- 「每条边只声明一次」与重复边的检测（含两端互相声明同一条边）。
- 文件所在文件夹与 `type` 是否一致的校验。
- 既有文件的文件名与 id 是否一致的校验（新建路径的 id 取法仍由
  `spec-00001-FR-53` 持有）。
- 全局覆盖率视图的筛选、排序与分组——只有 FR-10/FR-11 的按文档列出与展开。
- 覆盖率、异常与诊断的 CSV 或任何形式的导出。
- 归档门的回溯——仓库中既有的、无 `supersedes` 配对的 `archived` 文档既不被
  阻拦也不被提示（FR-4）。
- 撞 id 的自动修复或改 id 入口——白板只呈现与拒绝，修复靠按路径编辑（FR-9）。
- 把各文件夹 README 的 Relations 约定提为 `rule-00001` 的 BR（§3 的裁定：
  本轮不做）。
- 从 `spec-00001` §6 移除被本 spec 覆盖的两条范围外事项——属 `spec-00001`
  自己的修订轮，由 plan 轮列为一项任务（§1）。
- `spec-00001` 已列的其余范围外事项一概照旧（多人协作、「拒绝」评审动作、
  多会话并行、commit 降噪等）。

## 7. Non-Functional

- 三处下钻（全局覆盖率视图、异常清单、诊断清单）的清单项都应可键盘到达与
  激活，不只靠鼠标；对话框应可按 Esc 关闭（FR-10、FR-13、FR-14）。
- 覆盖三态与异常/诊断的区分不只靠颜色传达（沿用 `spec-00001-FR-32` 的口径）。
- 两道门的拒绝都不产生任何 commit，也不留下半写的文件。
- 正文解析结果（条目与验收行）应按变更失效缓存，使全局覆盖率视图不为每次
  请求重读整棵 docs 树——它一次读取全部 spec/rule，是现有读取里最重的一处。
  失效条件与缓存位置由 design-00001 的本轮修订持有。

## Links

- Parent: [prd-00001-docs-whiteboard](../prd/prd-00001-docs-whiteboard.md)
- Rules: [rule-00001-docs-workflow](../rule/rule-00001-docs-workflow.md)
- Design: [design-00001-docs-whiteboard](../design/design-00001-docs-whiteboard.md) · [design-00002-whiteboard-ui](../design/design-00002-whiteboard-ui.md)
- Sibling spec: [spec-00001-docs-whiteboard](spec-00001-docs-whiteboard.md)
- Blocked by: [issue-00004-duplicate-ids-hide-a-document](../issue/issue-00004-duplicate-ids-hide-a-document.md) · [issue-00015-open-questions-gate-bypassed-on-status-path](../issue/issue-00015-open-questions-gate-bypassed-on-status-path.md)
