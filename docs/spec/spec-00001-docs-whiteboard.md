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
  流程配置、Agent 会话、留痕。
- 输入：`parent` 为 [prd-00001-docs-whiteboard](../prd/prd-00001-docs-whiteboard.md)。
- 本 spec 收窄「文档」一词：白板上的文档指 `docs/**/*.md` 中带 id front matter
  的文件，不含各文件夹的 `README.md` 与 `TEMPLATE.md`。
- 节点标题取文档正文的第一个 H1；无 H1 时取文件名。

## 2. Stories

| Story | Value | Delivers |
| --- | --- | --- |
| S1 | 作为文档负责人，我要一打开白板就看到全部文档的关系图与状态，这样无需逐个翻文件就能看清依赖链与卡点 | spec-00001-FR-1, spec-00001-FR-2, spec-00001-FR-3 |
| S2 | 作为文档负责人，我要在白板上直接编辑文档正文，这样评审与修改不用切换工具 | spec-00001-FR-4, spec-00001-FR-5 |
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
  节点图，并自动布局；节点上展示类型、id、标题与 status。
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

## 5. Technical Design

| Design | Doc | Covers |
| --- | --- | --- |
| Docs 白板 MVP | [design-00001-docs-whiteboard](../design/design-00001-docs-whiteboard.md) | 服务形态、模块结构、流程配置契约、终端通道、权限传递、冲突与 commit 策略 |

## 6. Out of Scope

- 多人协作、远程部署、账号体系（见 PRD）。
- 「拒绝」评审动作。
- 在白板内编辑 `docs/` 之外的文件。
- `active → archived` 的归档配对自动化——MVP 只保证合法流转可选，不强制
  `rule-00001-BR-19` 的 `supersedes` 配对检查。
- commit 合并/降噪策略（FR-14 固定为最细粒度）。
- 写权限范围的用户自定义配置（后续版本；MVP 固定默认「仅 `docs/`」）。
- 越界写入的 git 回滚兜底（依赖 CLI 权限机制，见 FR-13）。

## 7. Non-Functional

- 图随文档规模增长仍可读：支持缩放、平移与聚焦（定位并高亮指定节点）。
- 节点状态一眼可辨：按 status 着色或同等显著的视觉区分。
- 内嵌终端体验接近本地终端：流式输出、可输入交互（可验证部分见 FR-12）。
- 白板之外直接改文件后，刷新即可反映最新状态，不产生第二套数据。

## Links

- Rules: [rule-00001-docs-workflow](../rule/rule-00001-docs-workflow.md)
- Design: [design-00001-docs-whiteboard](../design/design-00001-docs-whiteboard.md)
- Plan: [plan-00001-docs-whiteboard-mvp](../plan/plan-00001-docs-whiteboard-mvp.md)
