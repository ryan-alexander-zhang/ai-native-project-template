---
id: rule-00001-docs-workflow
type: rule
status: active
informs: [spec-00001-docs-whiteboard, spec-00006-whiteboard-co-write]
---

# Rule: docs 工作流

> 一个 docs 文档在什么种类下允许哪些状态流转、评审动作与答疑意味着什么、每个
> 阶段的下一步是什么、新文档的 id 如何取——与任何软件无关的流程规则。

## 1. Applicability

- Applies to: `docs/**/*.md` 中带 id front matter 的文档的状态、评审、写作
  动作与阶段推进。
- Does not apply to: 各文件夹的 `README.md` 与 `TEMPLATE.md`、repo 根部的规范
  文档（`DOCUMENT.md` 等）。

## 2. Terms

| Term | Definition |
| --- | --- |
| living doc | 长期演进、以「当前有效版本」为完成态的文档种类（见 BR-1） |
| work item | 以「做完」为完成态的工作项文档种类（见 BR-1） |
| 促进 | 把文档状态沿合法流转向前推一步 |
| 终态 | 不再允许任何流转的状态 |
| 下一步候选 | 从某类型文档可直接推进出的下一阶段文档类型 |
| 交付范围 | 一个 plan 声明的、其完成所须验证的需求条目集合（见 BR-24） |
| 落地写入 | 共写会话收束时随该次 commit 进入仓库的文件变更（见 BR-30） |

## 3. Rules

- **rule-00001-BR-1** (Definition) 文档种类二分：`spec`、`design`、`rule`、
  `decision`、`prd`、`idea`、`analysis`、`integration`、`reference`、
  `operation`、`record`、`prompt`、`report` 为 living doc；`issue`、`plan`、
  `task` 为 work item。

### rule-00001-BR-2 … BR-9 (Decision) 状态流转：某种类、某当前状态下允许的目标状态

Hit policy: `UNIQUE`

| # | 种类 | 当前状态 | 允许的目标状态 |
| --- | --- | --- | --- |
| **rule-00001-BR-2** | living doc | `draft` | `active`、`archived` |
| **rule-00001-BR-3** | living doc | `active` | `draft`（修订轮，第十一轮增，decision-00008）、`archived` |
| **rule-00001-BR-4** | work item | `draft` | `open`、`wontfix`、`archived` |
| **rule-00001-BR-5** | work item | `open` | `resolved`、`wontfix`、`archived` |
| **rule-00001-BR-6** | work item | `resolved` | `archived` |
| **rule-00001-BR-7** | work item | `wontfix` | `archived` |
| **rule-00001-BR-8** | — | `archived` | （无；终态） |
| **rule-00001-BR-9** | *(otherwise)* | — | 非法组合；不允许任何流转 |

- **rule-00001-BR-10** (Definition) 接收：对 `draft` 文档的促进——living doc
  促为 `active`，work item 促为 `open`。
- **rule-00001-BR-11** (Definition) 澄清：对 `draft` 文档发起的逐题提问——一次
  只问一题，每题附提问方的推荐答案；确认的未决点全部记入该文档的
  Open Questions，答案已给出既定结论的直接修订文档正文；文档保持 `draft`。
- **rule-00001-BR-12** (Constraint) 带未决 Open Questions 的文档不得被促进出
  `draft`。「未决」的判定：文档存在内容非空的 Open Questions 小节（各模板
  约定"问题全部关闭即删除该小节"，故小节存在且有条目即未决）。On violation:
  促进被拒绝。已促进的文档新增未决问题时不回退，仅在其后续促进时把关。

### rule-00001-BR-13 … BR-17 (Decision) 产品流：某类型文档的下一步候选

Hit policy: `UNIQUE`

| # | 来源类型 | 下一步候选 | 新文档携带的关系 |
| --- | --- | --- | --- |
| **rule-00001-BR-13** | `idea` | `prd`、`spec` | `parent` 指向来源 idea |
| **rule-00001-BR-14** | `prd` | `spec` | `parent` 指向来源 prd |
| **rule-00001-BR-15** | `spec` | `rule`、`design`、`plan` | `rule`/`design` 以 `informs` 回指来源 spec；`plan` 以 `implements` 指向来源 spec |
| **rule-00001-BR-16** | `plan` | `task`、`issue`、`record` | `task`/`record` 以 `parent` 指向来源 plan；`issue` 以 `blocks` 回指来源 plan |
| **rule-00001-BR-17** | *(otherwise)* | （无下一步） | — |

- **rule-00001-BR-18** (Definition) 新文档 id：`<type>-<五位数>-<slug>`，五位数
  取该类型现有最大编号加一（该类型无存量时为 `00001`）；slug 为小写连字符串，
  语义自取。
- **rule-00001-BR-19** (Constraint) 文档得处于 `archived` 的前提是仓库中存在
  以 `supersedes` 列出其 id 的替代文档（`archived` 意为「被替代」，不是
  「被否决」或「做完」）。On violation: 归档被拒绝。
- **rule-00001-BR-20** (Constraint) 澄清只适用于 `idea`、`prd`、`spec`、
  `rule`、`design` 五种类型——承载意图与决策的文档才有业务问题可问；其余类型
  承载事实、结果或执行，不适用。On violation: 澄清被拒绝。
- **rule-00001-BR-21** (Definition) 答疑：就一份文档提出问题以理解其内容，
  可就同一问题追问；只读——不修订文档，修订走编辑、推进、澄清、审计或
  共写（BR-28，`decision-00015` 增）；不是评审动作，适用于任意类型与任意状态，
  不改变文档状态。（第二十一轮改写为只读形态，`decision-00012`；原「按对话
  结论修订文档」半句随终端答疑退役移除。）
- **rule-00001-BR-22** (Definition) 审计：与接收、澄清并列的第三种评审动作
  ——由未撰写该文档的一方对照其类型文件夹的 README 审查结构与文法，再审内容
  本身；缺失的规则、用例与 GWT、未经确认的读数、无法确认的值，未决者逐条
  记入该文档的 Open Questions，已有既定结论者直接修订正文；文档保持
  `draft`（审计不促进状态，未决发现由 BR-12 在其后的接收时把关）。「未撰写
  该文档」这一前提由人的流程保证，工具不校验作者身份——工具能承载的是以
  未撰写者的立场审查的指令约束。
- **rule-00001-BR-23** (Constraint) 审计只适用于 `spec`、`rule`、`design`
  三种类型的 `draft` 文档——spec 与 rule 有条目文法与 GWT 的结构可审，
  design 有取舍与模块边界的内容可审；其余类型承载事实、结果或执行，无可
  对照的审计约定。On violation: 审计被拒绝。
- **rule-00001-BR-24** (Definition) plan 的交付范围：其 `implements` 所列
  目标中，指向 spec/rule 需求条目 id（`spec-<n>-FR-<i>` / `rule-<n>-BR-<i>`）
  的，该条目计入范围；指向存在的 AC id 的，其**所属条目**计入范围；指向
  整份 spec 或 rule 文档 id 的，该文档的**全部**需求条目计入范围；指向其他
  类型文档（如 design、report）的，不计入范围。
- **rule-00001-BR-25** (Constraint) plan 的 `open → resolved` 前提：
  以 `parent` 指向该 plan 的 record 的验收行为证据，交付范围内每个条目均
  判为已验证（该条目的全部 AC 各有通过的验收行，无一缺失、无一未通过）；
  范围中无法解析的 id（既不是存在的 spec/rule 文档，也不是存在的条目）视为
  缺口。交付范围为空的 plan 不受本约束（照常流转）。On violation: 流转被
  拒绝，拒绝理由逐条点名缺口条目。
- **rule-00001-BR-26** (Definition) 流程入口类型与新建：流程入口类型恰为
  `idea`、`prd`、`design`、`analysis`（design 与 analysis 第十四轮增，
  `plan-00016`）与 `decision`、`integration`、`reference`、`operation`、
  `prompt`、`report`（`decision-00014` 增，补齐出生通路）——idea 与 prd
  是产品流的起点（项目从 idea 进入产品流，或跳过 idea 直接从 prd 开始，
  `docs/README.md` 的既有约定）；其余八种在产品流（BR-13…BR-17）中不是
  任何类型的下一步候选，无推进通路可走，入口是它们的出生方式（`reference`
  另可由共写会话新建，BR-30）：
  design 与 analysis 承载先于任何 spec 的思考（design 的 `informs` 可留空
  待拾取、analysis 的 `parent` 可为空——各自文件夹 README 的既有约定）；
  decision 以 `motivated_by` 回指促成它的文档，新建时无来源可指则按
  `docs/README.md` 的空字段约定省略——来源可以是一次评审对话（decision
  文件夹 README 的既有约定）；operation 以 `implements` 指向它执行的
  decision 或 design（其文件夹 README 的既有约定），而二者均无下一步候选
  （BR-17），故 operation 同样无法经推进产生。流程入口类型的文档可不经
  推进直接**新建**：id 按 BR-18 取号、slug 自取，正文自该类型模板起草，
  初始 status 为 `draft`。入口身份不把该类型移出产品流的下一步候选
  （design 仍是 BR-15 的 next）——两条通路的差别只在是否携带指回来源的
  关系：推进来的带、新建的不带。
- **rule-00001-BR-27** (Constraint) 非入口类型不得新建——它们经产品流推进
  （BR-13…BR-17）产生，携带指回来源的关系。On violation: 新建被拒绝。
- **rule-00001-BR-28** (Definition) 共写：对一份文档发起的多轮协作写作
  会话——agent 按用户在对话中给出的意图与材料，起草或改写该文档内容
  （写域边界见 BR-30）；适用于任意类型；不是评审动作，不改变文档状态。
  材料的给法：对话中粘贴、报仓内文档 id、给仓库外绝对路径或 URL（仓外
  读取的授权由所选 CLI 承载，见 `spec-00006-FR-7`）。会话之间的载体只有
  文档本身，材料不构成会话间记忆——材料价值经正文或 `reference` 文档
  沉淀，落地形态见 `spec-00006-FR-1`。（`decision-00015` 增。）
- **rule-00001-BR-29** (Constraint) 共写只可对 `draft` 文档、或 `open`
  状态的 work item 发起——`active` 的 living doc 须先经修订轮（BR-3）
  转回 `draft`；`resolved`、`wontfix` 与 `archived` 的文档不可共写。
  On violation: 发起被拒绝。
- **rule-00001-BR-30** (Constraint) 共写会话的落地写入仅限目标文档与新建
  的 `reference` 文档——新建 `reference` 按 BR-26 的新建语义产生（id 按
  BR-18 取号、正文自 `reference` 模板起草、初始 status 为 `draft`）；
  目标文档 front matter 的 `id` 与 `status` 不得改动。On violation:
  越界写入不得落地，其余在域内的写入照常落地。

## 4. Acceptance (GWT)

- **rule-00001-AC-1.1** (rule-00001-BR-1)
  Given 一个 `prd` 文档
  When 判定其种类
  Then 它是 living doc
- **rule-00001-AC-1.2** (rule-00001-BR-1)
  Given 一个 `issue` 文档
  When 判定其种类
  Then 它是 work item
- **rule-00001-AC-2.1** (rule-00001-BR-2)
  Given 一个 `draft` 的 `design` 文档
  When 查询允许的目标状态
  Then 恰为 `active` 与 `archived`
- **rule-00001-AC-3.1** (rule-00001-BR-3)
  Given 一个 `active` 的 `decision` 文档
  When 查询允许的目标状态
  Then 恰为 `draft` 与 `archived`
- **rule-00001-AC-3.2** (rule-00001-BR-3)
  Given 一个 `active` 的 `spec` 文档经状态流转转为 `draft`（修订轮）
  When 对它发起审计
  Then 审计成立（BR-23 按 `draft` 的既有语义适用）
- **rule-00001-AC-3.3** (rule-00001-BR-3)
  Given 同 AC-3.2 的文档
  When 对它发起澄清
  Then 澄清成立（BR-20 适用）
- **rule-00001-AC-3.4** (rule-00001-BR-3)
  Given 同 AC-3.2 的文档带未决 Open Questions
  When 执行接收
  Then 接收被拒绝（BR-12 适用）
- **rule-00001-AC-3.5** (rule-00001-BR-3)
  Given 同 AC-3.2 的文档修订完成、无未决 Open Questions
  When 执行接收
  Then 其状态回到 `active`（active → draft → active 的完整修订轮）
- **rule-00001-AC-4.1** (rule-00001-BR-4)
  Given 一个 `draft` 的 `plan` 文档
  When 查询允许的目标状态
  Then 恰为 `open`、`wontfix`、`archived`
- **rule-00001-AC-5.1** (rule-00001-BR-5)
  Given 一个 `open` 的 `issue` 文档
  When 查询允许的目标状态
  Then 恰为 `resolved`、`wontfix`、`archived`
- **rule-00001-AC-6.1** (rule-00001-BR-6)
  Given 一个 `resolved` 的 `task` 文档
  When 查询允许的目标状态
  Then 恰为 `archived`
- **rule-00001-AC-7.1** (rule-00001-BR-7)
  Given 一个 `wontfix` 的 `issue` 文档
  When 查询允许的目标状态
  Then 恰为 `archived`
- **rule-00001-AC-8.1** (rule-00001-BR-8)
  Given 一个 `archived` 的 `idea` 文档
  When 查询允许的目标状态
  Then 为空集
- **rule-00001-AC-9.1** (rule-00001-BR-9)
  Given 一个 status 为词汇表之外值（如 `review`）的 `spec` 文档
  When 查询允许的目标状态
  Then 组合非法，不允许任何流转
- **rule-00001-AC-9.2** (rule-00001-BR-9)
  Given 一个 status 为 `open`（work item 词汇）的 `prd`（living doc）
  When 查询允许的目标状态
  Then 组合非法，不允许任何流转
- **rule-00001-AC-10.1** (rule-00001-BR-10)
  Given 一个 `draft` 的 prd
  When 执行接收
  Then 其状态为 `active`
- **rule-00001-AC-10.2** (rule-00001-BR-10)
  Given 一个 `draft` 的 issue
  When 执行接收
  Then 其状态为 `open`
- **rule-00001-AC-11.1** (rule-00001-BR-11)
  Given 一个 `draft` 文档的澄清确认了两条未决点
  When 澄清收尾
  Then 两条均在该文档的 Open Questions 中，状态仍为 `draft`
- **rule-00001-AC-11.2** (rule-00001-BR-11)
  Given 澄清中某题的答案给出了既定结论
  When 澄清收尾
  Then 该结论体现在文档正文中，不作为未决点进入 Open Questions
- **rule-00001-AC-12.1** (rule-00001-BR-12)
  Given 一个无未决 Open Questions 的 `draft` 文档
  When 执行接收
  Then 促进成功
- **rule-00001-AC-12.2** (rule-00001-BR-12)
  Given 一个带未决 Open Questions 的 `draft` 文档
  When 执行接收
  Then 促进被拒绝
- **rule-00001-AC-13.1** (rule-00001-BR-13)
  Given 一个 idea 文档
  When 查询下一步候选
  Then 恰为 `prd` 与 `spec`，且新文档的 `parent` 指向该 idea
- **rule-00001-AC-14.1** (rule-00001-BR-14)
  Given 一个 prd 文档
  When 查询下一步候选
  Then 恰为 `spec`，且新 spec 的 `parent` 指向该 prd
- **rule-00001-AC-15.1** (rule-00001-BR-15)
  Given 一个 spec 文档
  When 查询下一步候选
  Then 恰为 `rule`、`design`、`plan`
- **rule-00001-AC-15.2** (rule-00001-BR-15)
  Given 从某 spec 推进出一个 rule（或 design）
  When 查看新文档的关系
  Then 它以 `informs` 回指该 spec
- **rule-00001-AC-15.3** (rule-00001-BR-15)
  Given 从某 spec 推进出一个 plan
  When 查看新文档的关系
  Then 它以 `implements` 指向该 spec
- **rule-00001-AC-16.1** (rule-00001-BR-16)
  Given 一个 plan 文档
  When 查询下一步候选
  Then 恰为 `task`、`issue`、`record`
- **rule-00001-AC-16.2** (rule-00001-BR-16)
  Given 从某 plan 推进出一个 task（或 record）
  When 查看新文档的关系
  Then 它以 `parent` 指向该 plan
- **rule-00001-AC-16.3** (rule-00001-BR-16)
  Given 从某 plan 推进出一个 issue
  When 查看新文档的关系
  Then 它以 `blocks` 回指该 plan
- **rule-00001-AC-17.1** (rule-00001-BR-17)
  Given 一个 record 文档
  When 查询下一步候选
  Then 无下一步
- **rule-00001-AC-18.1** (rule-00001-BR-18)
  Given 仓库中 prd 类型现有最大编号为 `00001`
  When 推进出一个新的 prd
  Then 其 id 为 `prd-00002-<slug>`
- **rule-00001-AC-18.2** (rule-00001-BR-18)
  Given 仓库中没有任何 `task` 类型文档
  When 推进出一个新的 task
  Then 其 id 为 `task-00001-<slug>`
- **rule-00001-AC-19.1** (rule-00001-BR-19)
  Given 文档 B 的 `supersedes` 列出文档 A 的 id
  When 将 A 置为 `archived`
  Then 归档成立
- **rule-00001-AC-19.2** (rule-00001-BR-19)
  Given 仓库中没有任何文档 `supersedes` 文档 A
  When 将 A 置为 `archived`
  Then 归档被拒绝
- **rule-00001-AC-20.1** (rule-00001-BR-20)
  Given 一个 `draft` 的 `prd` 文档
  When 发起澄清
  Then 澄清成立
- **rule-00001-AC-20.2** (rule-00001-BR-20)
  Given 一个 `draft` 的 `record` 文档
  When 发起澄清
  Then 澄清被拒绝
- **rule-00001-AC-21.1** (rule-00001-BR-21)
  Given 一个 `active` 的 `record` 文档
  When 发起答疑
  Then 答疑成立
- **rule-00001-AC-21.2** (rule-00001-BR-21)
  Given 同 AC-21.1
  When 答疑结束
  Then 该文档状态仍为 `active`
- **rule-00001-AC-21.3** (rule-00001-BR-21)
  Given （已退役，仅为历史 record 归属保留——修订能力随第二十一轮移除，
  答疑只读，`spec-00005-FR-4`，其测试随 plan-00021 删除）答疑的对话
  得出一条修订结论
  When 答疑收尾
  Then 该结论体现在文档正文中
- **rule-00001-AC-22.1** (rule-00001-BR-22)
  Given 一个 `draft` 文档的审计确认了两条未决发现
  When 审计收尾
  Then 两条均在该文档的 Open Questions 中，状态仍为 `draft`
- **rule-00001-AC-22.2** (rule-00001-BR-22)
  Given 审计中某发现已有既定结论
  When 审计收尾
  Then 该结论体现在文档正文中，不作为未决点进入 Open Questions
- **rule-00001-AC-23.1** (rule-00001-BR-23)
  Given 一个 `draft` 的 `spec` 文档
  When 发起审计
  Then 审计成立
- **rule-00001-AC-23.2** (rule-00001-BR-23)
  Given 一个 `draft` 的 `prd` 文档
  When 发起审计
  Then 审计被拒绝
- **rule-00001-AC-23.3** (rule-00001-BR-23)
  Given 一个 `active` 的 `spec` 文档
  When 发起审计
  Then 审计被拒绝
- **rule-00001-AC-24.1** (rule-00001-BR-24)
  Given 一个 plan 的 `implements` 列出 `spec-00001-FR-50` 与一份 design 文档 id
  When 解析其交付范围
  Then 范围恰为 `spec-00001-FR-50` 一个条目
- **rule-00001-AC-24.2** (rule-00001-BR-24)
  Given 一个 plan 的 `implements` 列出一份含三个 BR 条目的 rule 文档 id
  When 解析其交付范围
  Then 该 rule 的三个条目全部计入范围
- **rule-00001-AC-24.3** (rule-00001-BR-24)
  Given 一个 plan 的 `implements` 只列出 design 文档 id
  When 解析其交付范围
  Then 范围为空
- **rule-00001-AC-24.4** (rule-00001-BR-24)
  Given 一个 plan 的 `implements` 列出 `spec-00001-AC-52.1`
  When 解析其交付范围
  Then 范围恰为其所属条目 `spec-00001-FR-52`
- **rule-00001-AC-25.1** (rule-00001-BR-25)
  Given 一个 `open` 的 plan，其交付范围内每个条目的全部 AC 各有一条通过的
  验收行，且这些行都来自 `parent` 指向该 plan 的 record
  When 促进为 `resolved`
  Then 流转成功
- **rule-00001-AC-25.2** (rule-00001-BR-25)
  Given 一个 `open` 的 plan，其交付范围内某条目的某 AC 没有任何验收行
  When 促进为 `resolved`
  Then 流转被拒绝，拒绝理由点名该条目
- **rule-00001-AC-25.3** (rule-00001-BR-25)
  Given 一个 `open` 的 plan，其交付范围内某 AC 的验收行存在但未通过
  When 促进为 `resolved`
  Then 流转被拒绝，拒绝理由点名该条目
- **rule-00001-AC-25.4** (rule-00001-BR-25)
  Given 一个 `open` 的 plan，其范围内条目的通过验收行只存在于 `parent`
  指向**另一个** plan 的 record 中
  When 促进为 `resolved`
  Then 流转被拒绝
- **rule-00001-AC-25.5** (rule-00001-BR-25)
  Given 一个 `open` 的 plan，其 `implements` 列出一个无法解析的条目 id
  When 促进为 `resolved`
  Then 流转被拒绝，拒绝理由点名该 id
- **rule-00001-AC-25.6** (rule-00001-BR-25)
  Given 一个 `open` 的 plan，其交付范围为空
  When 促进为 `resolved`
  Then 流转成功
- **rule-00001-AC-25.7** (rule-00001-BR-25)
  Given 一个 `open` 的 plan，其交付范围的覆盖分散在两份 `parent` 均指向该
  plan 的 record 中、合并后每条 AC 均有通过行
  When 促进为 `resolved`
  Then 流转成功（证据取并集）
- **rule-00001-AC-26.1** (rule-00001-BR-26)
  Given 仓库中 idea 现有最大编号为 `00001`
  When 新建一个 idea
  Then 新文档 id 为 `idea-00002-<slug>`，status 为 `draft`，正文自
  idea 模板起草
- **rule-00001-AC-26.2** (rule-00001-BR-26)
  Given 仓库中 design 现有最大编号为 `00002`，且仓库中不存在任何 spec
  When 新建一个 design
  Then 新文档 id 为 `design-00003-<slug>`，status 为 `draft`，正文自
  design 模板起草
- **rule-00001-AC-26.3** (rule-00001-BR-26)
  Given 一次评审对话促成了一个取舍，没有任何文档可作为其来源
  When 新建一个 decision
  Then 新文档 status 为 `draft`，front matter 不出现 `motivated_by` 字段，
  文档仍合式
- **rule-00001-AC-26.4** (rule-00001-BR-26)
  Given 仓库中存在一份 `active` 的 spec
  When 新建一个 design（而非自该 spec 推进出 design）
  Then 新文档的 `informs` 不指向该 spec——新建不携带指回来源的关系，
  与经 BR-15 推进出的 design 相区别
- **rule-00001-AC-27.1** (rule-00001-BR-27)
  Given 任意仓库状态
  When 请求新建一个 `spec`（非入口类型）
  Then 新建被拒绝
- **rule-00001-AC-28.1** (rule-00001-BR-28)
  Given 一份 `draft` 的 decision 文档
  When 对它发起共写并在对话中给出修改意图
  Then 会话按意图改写其正文，状态仍为 `draft`
- **rule-00001-AC-28.2** (rule-00001-BR-28)
  Given 一份 `open` 的 plan 文档
  When 对它发起共写并在对话中给出修改意图
  Then 会话按意图改写其正文，状态仍为 `open`
- **rule-00001-AC-28.3** (rule-00001-BR-28)
  Given 发起共写时以粘贴文本、仓内文档 id、仓库外 URL 各给一份材料
  When 会话启动
  Then 三份材料均进入会话的任务输入
- **rule-00001-AC-29.1** (rule-00001-BR-29)
  Given 一份 `draft` 的 report 文档
  When 发起共写
  Then 共写成立
- **rule-00001-AC-29.2** (rule-00001-BR-29)
  Given 一份 `open` 的 issue 文档
  When 发起共写
  Then 共写成立
- **rule-00001-AC-29.3** (rule-00001-BR-29)
  Given 一份 `active` 的 design 文档
  When 发起共写
  Then 发起被拒绝
- **rule-00001-AC-29.4** (rule-00001-BR-29)
  Given 一份 `resolved` 的 task 文档
  When 发起共写
  Then 发起被拒绝
- **rule-00001-AC-30.1** (rule-00001-BR-30)
  Given 一个共写会话的产出改写了目标文档并新建了一份合式的 `reference`
  文档
  When 会话收束落地
  Then 两份写入均落地
- **rule-00001-AC-30.2** (rule-00001-BR-30)
  Given 一个共写会话的产出试图写入目标之外的另一份既有文档
  When 会话收束落地
  Then 该越界写入不落地，目标文档的域内写入照常落地
- **rule-00001-AC-30.3** (rule-00001-BR-30)
  Given 一个共写会话的产出试图新建一份非 `reference` 类型的文档
  When 会话收束落地
  Then 该新建不落地
- **rule-00001-AC-30.4** (rule-00001-BR-30)
  Given 一个共写会话新建的 `reference` 的 id 未按 BR-18 取号（与既有 id
  冲突）
  When 会话收束落地
  Then 该 `reference` 不落地，域内其余写入照常落地
- **rule-00001-AC-30.5** (rule-00001-BR-30)
  Given 一个共写会话的产出改动了目标文档 front matter 的 `status`
  When 会话收束落地
  Then 该改动不落地，正文改动照常落地

## Open Questions

无。

## Links

- Consumed by: spec-00001-docs-whiteboard、spec-00006-whiteboard-co-write
