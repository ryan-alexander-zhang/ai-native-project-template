---
id: spec-00001-docs-whiteboard
type: spec
status: active
parent: prd-00001-docs-whiteboard
---

# Spec: Docs 白板 MVP

> 本地单人白板：把 `docs/` 可视化为节点图，支持编辑、状态切换、评审（接收/澄清）、
> 按流程配置推进下一步并调起受限的 agent 会话，全部变更自动留痕。

## 1. Context

- canonical terms 见 `CONTEXT.md`：白板、节点、评审动作、接收、澄清、推进、
  流程配置、Agent 会话、留痕、预览。
- 本 spec 的 Markdown 方言取 GFM。
- 输入：`parent` 为 [prd-00001-docs-whiteboard](../prd/prd-00001-docs-whiteboard.md)。
- 本 spec 收窄「文档」一词：白板上的文档指 `docs/**/*.md` 中带 id front matter
  的文件，不含各文件夹的 `README.md` 与 `TEMPLATE.md`。
- 节点标题取文档正文的第一个 H1；无 H1 时取文件名。

## 2. Stories

| Story | Value | Delivers |
| --- | --- | --- |
| S1 | 作为文档负责人，我要一打开白板就看到全部文档的关系图与状态，并能快速找到其中一份，这样无需逐个翻文件就能看清依赖链与卡点 | spec-00001-FR-1, spec-00001-FR-2, spec-00001-FR-3, spec-00001-FR-26, spec-00001-FR-27 |
| S2 | 作为文档负责人，我要在白板上直接编辑并预览文档正文，这样查看与修改不用切换工具 | spec-00001-FR-4, spec-00001-FR-5, spec-00001-FR-22, spec-00001-FR-23, spec-00001-FR-24, spec-00001-FR-25 |
| S3 | 作为文档负责人，我要在节点上合法地切换状态并做接收/澄清，这样把关动作由工具保证合规且显式 | spec-00001-FR-6, spec-00001-FR-7, spec-00001-FR-8, spec-00001-FR-9, spec-00001-FR-19 |
| S4 | 作为文档负责人，我要从节点一键推进下一步并看着 agent 实时写文档，这样流程知识不靠记忆 | spec-00001-FR-10, spec-00001-FR-11, spec-00001-FR-12, spec-00001-FR-13, spec-00001-FR-15, spec-00001-FR-16, spec-00001-FR-17, spec-00001-FR-18, spec-00001-FR-21 |
| S5 | 作为文档负责人，我要每次变更与评审都自动留痕，这样任何结论都可追溯 | spec-00001-FR-14, spec-00001-FR-20 |

## 3. Business Rules

| Rule set | Doc | Covers |
| --- | --- | --- |
| docs 工作流 | [rule-00001-docs-workflow](../rule/rule-00001-docs-workflow.md) | 文档种类二分、状态流转决策表、接收/澄清的含义、产品流下一步表、新文档 id 取法 |

流程配置（FR-15）承载其中的类型二分与产品流（BR-1、BR-13…BR-17）；状态流转
表（BR-2…BR-9）由文档种类内建推导，不进配置。二者均不得与规则冲突。

## 4. System Requirements

- **spec-00001-FR-1** (Event) 当白板加载或用户刷新时，系统应解析全部文档的
  front matter，按每文档一节点、每关系字段一边（关系字段集来自流程配置）渲染
  节点图，并自动布局；节点上展示类型、id、标题与 status。布局为：**列即文档的
  front matter `type`**（不取 id 前缀），列序取流程配置中类型的声明顺序，没有
  文档的类型不占列，`type` 缺失或不在配置内者排在全部已声明类型之后，方向
  左→右；**行为同列内的 id 升序**；每条边按 front matter 的声明方向连接两个
  节点，箭头指向被引用的那份文档；节点不提供手工连线。同一次加载内布局与配置
  同时到位后才落位，且同一组文档在刷新前后位置不变。完整的布局规则（含间距、
  同 id 的次级排序与异常桶内的排序）由 `decision-00002-whiteboard-layout` 持有，
  本要求只承载其可验收的部分。
- **spec-00001-FR-2** (Unwanted) 若文档的 front matter 缺失或非法（含 id 不合
  `<type>-<五位数>-<slug>` 格式、type 不在流程配置的类型集内），或关系字段指向
  不存在的文档 id，系统应将该节点或边标记为异常并保持其余图可用，不得整体失败；
  无 id 的节点以文件路径为标签，异常节点的浮窗只提供编辑入口（用于修复），不
  提供状态切换、评审与推进。
- **spec-00001-FR-3** (Event) 当用户点击节点时，系统应弹出浮窗工具栏，提供
  编辑、状态切换、评审（接收/澄清）与推进入口；点击画布空白处时工具栏关闭。
- **spec-00001-FR-4** (Event) 当用户在 Markdown 编辑器中保存时，系统应把内容
  写回对应文档文件。
- **spec-00001-FR-5** (Unwanted) 若文档文件在编辑器打开后已在磁盘上被修改或
  删除，系统应拒绝本次保存并呈现冲突，不得覆盖磁盘内容。
- **spec-00001-FR-6** (Event) 当用户打开状态切换时，系统应只提供该文档种类
  （living doc / work item）与当前状态下合法的目标状态，per
  `rule-00001-BR-2` … `rule-00001-BR-9`。
- **spec-00001-FR-7** (Unwanted) 若状态变更请求指定了非法流转，系统应拒绝该
  请求且不修改文件。
- **spec-00001-FR-8** (Event) 当用户对 `draft` 文档执行接收时，系统应按
  `rule-00001-BR-10` 促进状态：living doc 促为 `active`，work item 促为
  `open`；对非 `draft` 文档、或带未决 Open Questions 的文档（per
  `rule-00001-BR-12`）执行接收应被拒绝。
- **spec-00001-FR-9** (Event) 当用户对 `draft` 文档执行澄清并给出一条或多条
  待澄清点时，系统应按 `rule-00001-BR-11` 把全部待澄清点追加到该文档的
  Open Questions 小节（小节不存在时创建），status 保持 `draft`；对非 `draft`
  文档执行澄清应被拒绝。（MVP 中回灌 agent 由用户手动再次推进，不自动发起。）
- **spec-00001-FR-10** (Event) 当用户点击节点右侧「+」时，系统应按流程配置
  （承载 `rule-00001-BR-13` … `rule-00001-BR-17`）列出该文档类型的全部下一步
  候选类型；无候选时呈现"无下一步"且不发起任何会话。
- **spec-00001-FR-11** (Event) 当用户选定下一步类型时，系统应在内嵌终端中启动
  流程配置指定的本地 agent CLI 会话，任务指令中给定目标文档类型、id（取法按
  `rule-00001-BR-18`）与新文档应携带的关系（per `rule-00001-BR-13` …
  `rule-00001-BR-16`，指向来源文档）。
- **spec-00001-FR-12** (State) 当 agent 会话运行中时，内嵌终端应流式呈现其
  输出（无需用户手动刷新）并把用户输入转发给会话；会话进程退出时终端呈现结束
  状态且白板刷新节点图。
- **spec-00001-FR-13** (Event) 当 agent 会话启动时，系统应把流程配置中该 CLI
  的写权限约束传递给会话（MVP 默认约束为「仅 `docs/` 可写」）；越界写入由所选
  CLI 的权限机制拒绝，白板不做 git 回滚兜底。
- **spec-00001-FR-14** (Ubiquitous) 系统应把白板发起的每次变更落为 git commit，
  且只暂存本次动作涉及的文件：编辑、状态切换、接收、澄清为一动作一 commit，
  推进为一会话一 commit；commit 信息指明动作种类与文档 id。（本条裁决 PRD
  风险项「自动 commit 的噪音」：MVP 取最细粒度，合并策略留待后续版本。）
- **spec-00001-FR-15** (Unwanted) 系统应在启动时读取并校验流程配置（文档类型、
  关系字段、下一步映射、agent 命令与写权限约束）；若配置缺失或非法，系统应
  拒绝启动并给出指明问题所在的错误信息。
- **spec-00001-FR-16** (Unwanted) 若 agent CLI 不存在或启动失败，系统应在内嵌
  终端呈现错误，且不产生任何 commit。
- **spec-00001-FR-17** (Event) 当推进会话结束、白板刷新时，系统应校验会话产出
  的新文档 front matter（id 取法按 `rule-00001-BR-18`、关系按
  `rule-00001-BR-13` … `rule-00001-BR-16` 指向来源文档）；不合规的按 FR-2
  标记为异常。
- **spec-00001-FR-18** (Unwanted) 若已有 agent 会话在运行，再次发起推进应被
  拒绝，且不影响运行中的会话（MVP 同时仅一个会话）。
- **spec-00001-FR-19** (Unwanted) 若动作（状态切换、评审、推进）的目标文档在
  磁盘上已不存在，系统应拒绝该动作、提示刷新，且不产生 commit。
- **spec-00001-FR-20** (Unwanted) 若 git commit 失败（如仓库缺失、提交身份未
  配置），系统应呈现错误；已落盘的文件变更保留在工作区，不回滚。
- **spec-00001-FR-21** (State) 当浏览器与白板断开连接时，运行中的 agent 会话应
  在服务端存续；当白板重新打开时，用户应能回到该会话的终端（含此前输出）继续
  查看与交互。
- **spec-00001-FR-22** (Event) 当用户在编辑器中切换到预览时，系统应把编辑器
  当前缓冲区（未落盘的正文）按 GFM 渲染，至少包括标题、列表、表格、代码块，
  其中 `mermaid` 代码块渲染为图形，front matter 不作为正文渲染；预览与编辑
  互斥呈现。
- **spec-00001-FR-23** (Unwanted) 若某个 `mermaid` 代码块无法解析，系统应在该
  图的位置呈现错误块并含解析器给出的原因，文档其余部分（含其他图）照常渲染。
- **spec-00001-FR-24** (Ubiquitous) 系统应不在预览中执行文档携带的脚本，也不把
  文档中的原始 HTML 注入页面——原始 HTML 一律丢弃。
- **spec-00001-FR-25** (Event) 当用户从预览切回编辑时，系统应保持缓冲区正文、
  光标位置与滚动位置不变。
- **spec-00001-FR-26** (Event) 当用户在命令面板中输入检索词时，系统应列出 id 或
  标题以不区分大小写的子串方式包含该词的全部文档，按图中顺序排列，不截断。异常
  文档以其文件路径为 id（per FR-2），因此同样可被检索到。
- **spec-00001-FR-27** (Event) 当用户在命令面板中选定一个文档时，系统应把视口
  定位到该节点、选中它并关闭面板。

**Acceptance (GWT)**

- **spec-00001-AC-1.1** (spec-00001-FR-1)
  Given `docs/` 下有若干带合法 front matter 且相互引用的文档
  When 打开白板
  Then 每个文档呈现为一个节点，每个关系字段呈现为一条边
- **spec-00001-AC-1.2** (spec-00001-FR-1)
  Given 同上
  When 打开白板
  Then 节点位置由布局算法给出，无需手工摆放
- **spec-00001-AC-1.3** (spec-00001-FR-1)
  Given 某文件夹下存在 `README.md` 与 `TEMPLATE.md`
  When 打开白板
  Then 二者不出现为节点
- **spec-00001-AC-1.4** (spec-00001-FR-1)
  Given `docs/` 下没有任何文档
  When 打开白板
  Then 呈现空画布且无错误
- **spec-00001-AC-1.5** (spec-00001-FR-1)
  Given 一个正文首个 H1 为「Docs 白板 PRD」的文档
  When 打开白板
  Then 该节点标题为「Docs 白板 PRD」
- **spec-00001-AC-1.6** (spec-00001-FR-1)
  Given `idea`、`prd`、`spec` 各一份，且按流程配置的声明顺序位列前三
  When 打开白板
  Then 三者各占一列，x 依该顺序递增
- **spec-00001-AC-1.7** (spec-00001-FR-1)
  Given 同类型的 `spec-00001` 与 `spec-00002`
  When 打开白板
  Then 二者 x 相同，且 `spec-00001` 在 `spec-00002` 上方
- **spec-00001-AC-1.8** (spec-00001-FR-1)
  Given 配置中 `prd` 位于 `idea` 与 `spec` 之间，而 `docs/` 下没有任何 `prd`
  When 打开白板
  Then `idea` 与 `spec` 相邻成列，中间不留空列
- **spec-00001-AC-1.9** (spec-00001-FR-1)
  Given 一份 type 不在流程配置类型集内的文档与若干正常文档
  When 打开白板
  Then 该节点位于全部已声明类型的列之后
- **spec-00001-AC-1.10** (spec-00001-FR-1)
  Given `prd-00001` 的 front matter 声明 `parent: idea-00001`
  When 打开白板
  Then 该边的箭头落在 `idea-00001` 一端
- **spec-00001-AC-1.11** (spec-00001-FR-1)
  Given `spec-00002` 声明 `supersedes: [spec-00001]`，且 `spec-00001` 在其上方
  When 打开白板
  Then 该边自 `spec-00002` 的上锚点连到 `spec-00001` 的下锚点
- **spec-00001-AC-1.12** (spec-00001-FR-1)
  Given 一组文档已在白板上落位
  When 用户刷新
  Then 每个节点的位置与刷新前相同
- **spec-00001-AC-1.13** (spec-00001-FR-1)
  Given 一份不声明任何关系字段的文档
  When 打开白板
  Then 该节点仍按其类型落在对应列，且没有边连到它
- **spec-00001-AC-1.14** (spec-00001-FR-1)
  Given 任一节点
  When 用户在其锚点上拖拽
  Then 不产生任何边——锚点只用于定位既有的关系边
- **spec-00001-AC-2.1** (spec-00001-FR-2)
  Given 一个缺失 front matter 的文档与若干正常文档
  When 打开白板
  Then 该文档的节点带异常标记且以文件路径为标签，其余节点与边正常呈现
- **spec-00001-AC-2.2** (spec-00001-FR-2)
  Given 一个文档的关系字段指向不存在的 id
  When 打开白板
  Then 该边带异常标记，图整体仍可用
- **spec-00001-AC-2.3** (spec-00001-FR-2)
  Given 一个 id 不合 `<type>-<五位数>-<slug>` 格式的文档
  When 打开白板
  Then 该节点带异常标记，其余图可用
- **spec-00001-AC-2.4** (spec-00001-FR-2)
  Given 一个异常节点
  When 点击该节点
  Then 浮窗只含编辑入口，无状态切换、评审、推进
- **spec-00001-AC-3.1** (spec-00001-FR-3)
  Given 图上有一个正常节点
  When 点击该节点
  Then 弹出浮窗工具栏，含编辑、状态切换、评审、推进四个入口
- **spec-00001-AC-3.2** (spec-00001-FR-3)
  Given 浮窗工具栏已打开
  When 点击画布空白处
  Then 工具栏关闭
- **spec-00001-AC-4.1** (spec-00001-FR-4)
  Given 在编辑器中修改了某文档正文
  When 保存
  Then 磁盘上该文件内容为编辑后内容
- **spec-00001-AC-5.1** (spec-00001-FR-5)
  Given 编辑器已打开某文档，且该文件随后被磁盘上的其他进程修改
  When 保存
  Then 保存被拒绝并呈现冲突
- **spec-00001-AC-5.2** (spec-00001-FR-5)
  Given 同 AC-5.1
  When 保存被拒绝后查看磁盘
  Then 文件内容为外部修改后的版本
- **spec-00001-AC-5.3** (spec-00001-FR-5)
  Given 编辑器已打开某文档，且该文件随后在磁盘上被删除
  When 保存
  Then 保存被拒绝并呈现冲突
- **spec-00001-AC-6.1** (spec-00001-FR-6)
  Given 一个 `draft` 的 living doc（如 prd）
  When 打开状态切换
  Then 候选中含 `active` 且不含 `open`、`resolved`
- **spec-00001-AC-6.2** (spec-00001-FR-6)
  Given 一个 `draft` 的 work item（如 issue）
  When 打开状态切换
  Then 候选中含 `open` 且不含 `active`
- **spec-00001-AC-6.3** (spec-00001-FR-6)
  Given 一个 `active` 的 living doc
  When 打开状态切换
  Then 候选中含 `archived` 且不含 `resolved`、`open`
- **spec-00001-AC-6.4** (spec-00001-FR-6)
  Given 一个 `open` 的 work item
  When 打开状态切换
  Then 候选中含 `resolved` 与 `wontfix` 且不含 `active`
- **spec-00001-AC-7.1** (spec-00001-FR-7)
  Given 一个 `draft` 的 work item
  When 通过接口直接请求将其置为 `resolved`
  Then 请求被拒绝且文件内容不变
- **spec-00001-AC-8.1** (spec-00001-FR-8)
  Given 一个 `draft` 的 prd 节点
  When 执行接收
  Then 该文档 status 变为 `active`
- **spec-00001-AC-8.2** (spec-00001-FR-8)
  Given 一个 `draft` 的 issue 节点
  When 执行接收
  Then 该文档 status 变为 `open`
- **spec-00001-AC-8.3** (spec-00001-FR-8)
  Given 一个已是 `active` 的文档
  When 执行接收
  Then 动作被拒绝且文件不变
- **spec-00001-AC-8.4** (spec-00001-FR-8)
  Given 一个带未决 Open Questions 小节的 `draft` 文档
  When 执行接收
  Then 动作被拒绝且文件不变
- **spec-00001-AC-9.1** (spec-00001-FR-9)
  Given 一个含 Open Questions 小节的 `draft` 文档与一条待澄清点
  When 执行澄清
  Then 待澄清点出现在该小节，status 仍为 `draft`
- **spec-00001-AC-9.2** (spec-00001-FR-9)
  Given 一个无 Open Questions 小节的 `draft` 文档
  When 执行澄清
  Then 该小节被创建并含给出的待澄清点
- **spec-00001-AC-9.3** (spec-00001-FR-9)
  Given 一个 `draft` 文档与三条待澄清点
  When 执行澄清
  Then 三条全部出现在 Open Questions 小节
- **spec-00001-AC-9.4** (spec-00001-FR-9)
  Given 一个 `active` 文档
  When 执行澄清
  Then 动作被拒绝且文件不变
- **spec-00001-AC-10.1** (spec-00001-FR-10)
  Given 流程配置定义 prd 的下一步为 spec
  When 点击某 prd 节点的「+」
  Then 候选列表恰为 spec
- **spec-00001-AC-10.2** (spec-00001-FR-10)
  Given 流程配置定义 idea 的下一步为 prd 与 spec（per rule-00001-BR-13）
  When 点击某 idea 节点的「+」
  Then 两个候选全部列出
- **spec-00001-AC-10.3** (spec-00001-FR-10)
  Given 流程配置未给某类型定义下一步
  When 点击该类型节点的「+」
  Then 呈现"无下一步"且不发起任何会话
- **spec-00001-AC-11.1** (spec-00001-FR-11)
  Given 在某 idea 节点选定下一步类型 prd
  When 确认发起
  Then 内嵌终端中出现流程配置指定的 CLI 会话
- **spec-00001-AC-11.2** (spec-00001-FR-11)
  Given 同 AC-11.1，且 prd 类型现有最大编号为 00001
  When 会话启动
  Then 任务指令包含目标类型 prd、id `prd-00002-<slug>` 的格式要求与 `parent: <该 idea id>`
- **spec-00001-AC-12.1** (spec-00001-FR-12)
  Given 一个运行中的 agent 会话
  When CLI 产生输出
  Then 输出出现在内嵌终端，无需用户手动刷新
- **spec-00001-AC-12.2** (spec-00001-FR-12)
  Given 一个运行中的 agent 会话等待输入
  When 用户在内嵌终端输入并回车
  Then CLI 对该输入作出可观察的响应
- **spec-00001-AC-12.3** (spec-00001-FR-12)
  Given 一个运行中的 agent 会话
  When 会话进程退出
  Then 终端呈现结束状态
- **spec-00001-AC-12.4** (spec-00001-FR-12)
  Given 会话在运行期间新建了一个文档
  When 会话进程退出
  Then 节点图刷新并出现该新文档节点
- **spec-00001-AC-13.1** (spec-00001-FR-13)
  Given 流程配置对所选 CLI 定义了默认写权限约束「仅 `docs/`」
  When 会话启动
  Then 会话以该权限约束启动（可从 CLI 启动参数/权限配置观察）
- **spec-00001-AC-13.2** (spec-00001-FR-13)
  Given 一个以默认约束启动、且 CLI 支持权限机制的会话
  When 会话试图写 `docs/` 之外的文件（如 `src/x`）
  Then 该文件在工作区中保持不变
- **spec-00001-AC-13.3** (spec-00001-FR-13)
  Given 一个以默认约束启动的会话
  When 会话写 `docs/` 之内的文件
  Then 变更成功落盘
- **spec-00001-AC-14.1** (spec-00001-FR-14)
  Given 一次编辑器保存已完成
  When 查看 git 历史
  Then 最新 commit 的信息指明「编辑」与该文档 id
- **spec-00001-AC-14.2** (spec-00001-FR-14)
  Given 工作区存在一个与本次动作无关的脏文件
  When 一次编辑器保存完成后查看该 commit
  Then commit 只含该文档的变更，脏文件不在其中
- **spec-00001-AC-14.3** (spec-00001-FR-14)
  Given 一次接收已完成
  When 查看 git 历史
  Then 存在一次 commit，信息指明「接收」与该文档 id
- **spec-00001-AC-14.4** (spec-00001-FR-14)
  Given 一次推进会话结束且产生了 docs/ 变更
  When 查看 git 历史
  Then 存在一次含该会话全部变更的 commit，信息指明「推进」与新文档 id
- **spec-00001-AC-15.1** (spec-00001-FR-15)
  Given 流程配置文件不存在
  When 启动白板服务
  Then 启动失败，错误信息指明缺失的配置路径
- **spec-00001-AC-15.2** (spec-00001-FR-15)
  Given 流程配置内容非法（如引用未知文档类型）
  When 启动白板服务
  Then 启动失败，错误信息指明非法条目
- **spec-00001-AC-16.1** (spec-00001-FR-16)
  Given 流程配置指定的 agent CLI 在本机不存在
  When 发起推进
  Then 内嵌终端呈现启动失败的错误
- **spec-00001-AC-16.2** (spec-00001-FR-16)
  Given 同 AC-16.1
  When 查看 git 历史
  Then 本次推进未产生任何 commit
- **spec-00001-AC-17.1** (spec-00001-FR-17)
  Given 推进会话产出的新文档缺失 `parent`
  When 会话结束、白板刷新
  Then 该新文档节点带异常标记
- **spec-00001-AC-17.2** (spec-00001-FR-17)
  Given 推进会话产出的新文档 front matter 合规
  When 会话结束、白板刷新
  Then 该节点为正常节点且有指向来源文档的边
- **spec-00001-AC-18.1** (spec-00001-FR-18)
  Given 一个运行中的 agent 会话
  When 在另一节点发起推进
  Then 发起被拒绝，运行中的会话不受影响
- **spec-00001-AC-19.1** (spec-00001-FR-19)
  Given 某节点对应的文件已在磁盘上被删除
  When 对该节点执行接收
  Then 动作被拒绝并提示刷新，且无 commit 产生
- **spec-00001-AC-20.1** (spec-00001-FR-20)
  Given git 提交身份未配置
  When 一次编辑器保存触发 commit
  Then 系统呈现错误，且磁盘上保留编辑后的文件内容
- **spec-00001-AC-21.1** (spec-00001-FR-21)
  Given 一个运行中的 agent 会话
  When 关闭浏览器页面
  Then 会话进程持续运行，其后续文件产出照常落盘
- **spec-00001-AC-21.2** (spec-00001-FR-21)
  Given 断开期间会话仍在运行
  When 重新打开白板并进入该会话终端
  Then 终端呈现此前输出，且可继续输入交互
- **spec-00001-AC-22.1** (spec-00001-FR-22)
  Given 编辑器中打开一个含二级标题的文档
  When 切换到预览
  Then 该标题呈现为标题元素
- **spec-00001-AC-22.2** (spec-00001-FR-22)
  Given 编辑器中打开一个含无序列表的文档
  When 切换到预览
  Then 列表项逐条呈现为列表元素
- **spec-00001-AC-22.3** (spec-00001-FR-22)
  Given 编辑器中的文档含一个 GFM 表格
  When 切换到预览
  Then 该表格呈现为表格元素
- **spec-00001-AC-22.4** (spec-00001-FR-22)
  Given 编辑器中的文档含一个合法的 `mermaid` 代码块
  When 切换到预览
  Then 该块位置呈现为图形，而非代码文本
- **spec-00001-AC-22.5** (spec-00001-FR-22)
  Given 编辑器中的文档含一个非 `mermaid` 的代码块
  When 切换到预览
  Then 该块仍呈现为代码
- **spec-00001-AC-22.6** (spec-00001-FR-22)
  Given 编辑器中的文档带 front matter
  When 切换到预览
  Then 预览中不出现 front matter 的任何字段
- **spec-00001-AC-22.7** (spec-00001-FR-22)
  Given 编辑器中的文档正文已渲染
  When 切换到预览
  Then 编辑器的源码视图不再可见（互斥呈现）
- **spec-00001-AC-22.8** (spec-00001-FR-22)
  Given 缓冲区为空（例如文档尚未加载完成）
  When 切换到预览
  Then 预览区为空
- **spec-00001-AC-23.1** (spec-00001-FR-23)
  Given 文档含一个语法非法的 `mermaid` 代码块
  When 切换到预览
  Then 该块位置呈现一个错误块，内含解析器报出的原因
- **spec-00001-AC-23.2** (spec-00001-FR-23)
  Given 同 AC-23.1，且文档在该块之后还有正文
  When 切换到预览
  Then 该块之后的正文照常渲染
- **spec-00001-AC-23.3** (spec-00001-FR-23)
  Given 文档含一个非法与一个合法的 `mermaid` 代码块
  When 切换到预览
  Then 合法的那个仍呈现为图形
- **spec-00001-AC-23.4** (spec-00001-FR-23)
  Given 一个非法的 `mermaid` 块已在预览中呈现为错误
  When 把它改正后再次切换到预览
  Then 该位置呈现为图形
- **spec-00001-AC-24.1** (spec-00001-FR-24)
  Given 文档正文含 `<script>` 标签
  When 切换到预览
  Then 预览中不存在该 script 元素
- **spec-00001-AC-24.2** (spec-00001-FR-24)
  Given 文档的 `mermaid` 块节点标签内含 `<script>`
  When 切换到预览
  Then 预览中不存在该 script 元素
- **spec-00001-AC-24.3** (spec-00001-FR-24)
  Given 文档含原始 HTML 与普通正文
  When 切换到预览
  Then 普通正文照常渲染
- **spec-00001-AC-25.1** (spec-00001-FR-25)
  Given 预览中的文档在编辑器里已被改动但尚未保存
  When 切回编辑
  Then 编辑器仍持有改动后的正文
- **spec-00001-AC-25.2** (spec-00001-FR-25)
  Given 预览前编辑器中有一个光标位置
  When 切回编辑
  Then 光标仍在该位置
- **spec-00001-AC-26.1** (spec-00001-FR-26)
  Given 图上有一个 id 为 `idea-00001-whiteboard` 的文档
  When 在命令面板中输入 `idea-00001`
  Then 列表中出现该文档
- **spec-00001-AC-26.2** (spec-00001-FR-26)
  Given 图上有一个标题为「Docs Whiteboard PRD」的文档
  When 在命令面板中输入 `Whiteboard PRD`
  Then 列表中出现该文档
- **spec-00001-AC-26.3** (spec-00001-FR-26)
  Given 图上有一个 id 为 `idea-00001-whiteboard` 的文档
  When 输入大小写不同的 `IDEA-00001`
  Then 列表中出现该文档
- **spec-00001-AC-26.4** (spec-00001-FR-26)
  Given 图上有三个 id 以 `spec-` 开头的文档
  When 输入 `spec-`
  Then 三个文档全部出现在列表中
- **spec-00001-AC-26.5** (spec-00001-FR-26)
  Given 图上没有任何文档匹配某检索词
  When 输入该词
  Then 列表为空
- **spec-00001-AC-26.6** (spec-00001-FR-26)
  Given 同 AC-26.5
  When 输入该词
  Then 呈现「无匹配」
- **spec-00001-AC-27.1** (spec-00001-FR-27)
  Given 命令面板列出了一个文档
  When 选定它
  Then 该节点的浮窗工具栏出现（观察点依赖 FR-3）
- **spec-00001-AC-27.2** (spec-00001-FR-27)
  Given 命令面板列出了一个当前视口之外的文档
  When 选定它
  Then 视口移动到该节点
- **spec-00001-AC-27.3** (spec-00001-FR-27)
  Given 命令面板列出了一个文档
  When 选定它
  Then 命令面板关闭
- **spec-00001-AC-27.4** (spec-00001-FR-27)
  Given 命令面板的列表为空
  When 按下回车
  Then 不选中任何节点
- **spec-00001-AC-27.5** (spec-00001-FR-27)
  Given 同 AC-27.4
  When 按下回车
  Then 面板保持打开

## 5. Technical Design

| Design | Doc | Covers |
| --- | --- | --- |
| Docs 白板 MVP | [design-00001-docs-whiteboard](../design/design-00001-docs-whiteboard.md) | 服务形态、模块结构、流程配置契约、终端通道、权限传递、冲突与 commit 策略 |
| Docs 白板界面 | [design-00002-whiteboard-ui](../design/design-00002-whiteboard-ui.md) | 设计令牌、布局、控件映射、图标语言、可访问性 |

## 6. Out of Scope

- 多人协作、远程部署、账号体系（见 PRD）。
- 「拒绝」评审动作。
- 在白板内编辑 `docs/` 之外的文件。
- `active → archived` 的归档配对自动化——MVP 只保证合法流转可选，不强制
  `rule-00001-BR-19` 的 `supersedes` 配对检查。
- commit 合并/降噪策略（FR-14 固定为最细粒度）。
- **id 唯一性校验**。FR-2 的异常清单不含「两份文档撞 id」，因此撞 id 时其中一份
  在白板上不可见、且动作会落到另一份上——已知缺陷，见
  [issue-00004](../issue/issue-00004-duplicate-ids-hide-a-document.md)；纳入
  FR-2 需要一次呈现方式的裁定。
- 写权限范围的用户自定义配置（后续版本；MVP 固定默认「仅 `docs/`」）。
- 越界写入的 git 回滚兜底（依赖 CLI 权限机制，见 FR-13）。
- 编辑与预览分栏并实时联动（MVP 为互斥切换）。
- GFM 之外的 Markdown 扩展；`mermaid` 之外的图表语法。
- 预览打开期间对该文档的外部改动自动重渲染（切回编辑再切预览即取到最新缓冲区）。
- 文档中 `javascript:` 等 URL scheme 的拦截——FR-24 只承诺不执行脚本、不注入
  原始 HTML。

## 7. Non-Functional

- 图随文档规模增长仍可读：支持缩放与平移；定位到指定节点见 FR-27。
- 节点状态一眼可辨：按 status 着色或同等显著的视觉区分，且不只靠颜色传达。
- 内嵌终端体验接近本地终端：流式输出、可输入交互（可验证部分见 FR-12）。
- 白板之外直接改文件后，刷新即可反映最新状态，不产生第二套数据。

以下项按 [design-00002-whiteboard-ui](../design/design-00002-whiteboard-ui.md)
§8 的裁定不写 GWT——它们没有回归保护，这是明知的取舍：

- 呈现模式支持浅色、深色与跟随系统，偏好在本机保留。
- 编辑器与终端面板的尺寸可调，尺寸在本机保留。
- 文档检索入口可被发现（不必记住快捷键即可打开命令面板）。
- 进行中与空结果有可见的呈现形态：保存中、空画布、异常计数为零。空画布本身
  「无错误」由 AC-1.4 保证，此处只涉及它长什么样。
- 焦点样式统一，不依赖浏览器默认。

## Links

- Rules: [rule-00001-docs-workflow](../rule/rule-00001-docs-workflow.md)
- Design: [design-00001-docs-whiteboard](../design/design-00001-docs-whiteboard.md) · [design-00002-whiteboard-ui](../design/design-00002-whiteboard-ui.md)
- Plan: [plan-00001-docs-whiteboard-mvp](../plan/plan-00001-docs-whiteboard-mvp.md) · [plan-00002-whiteboard-ui](../plan/plan-00002-whiteboard-ui.md) · [plan-00003-whiteboard-relation-edges](../plan/plan-00003-whiteboard-relation-edges.md)
- Decisions: [decision-00001-whiteboard-ui-stack](../decision/decision-00001-whiteboard-ui-stack.md) · [decision-00002-whiteboard-layout](../decision/decision-00002-whiteboard-layout.md)（后者持有 FR-1 的完整布局规则）
