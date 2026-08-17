---
id: rule-00001-docs-workflow
type: rule
status: active
informs: [spec-00001-docs-whiteboard]
---

# Rule: docs 工作流

> 一个 docs 文档在什么种类下允许哪些状态流转、评审动作与答疑意味着什么、每个
> 阶段的下一步是什么、新文档的 id 如何取——与任何软件无关的流程规则。

## 1. Applicability

- Applies to: `docs/**/*.md` 中带 id front matter 的文档的状态、评审与阶段推进。
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
| **rule-00001-BR-3** | living doc | `active` | `archived` |
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
| **rule-00001-BR-16** | `plan` | `task` | `parent` 指向来源 plan |
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
- **rule-00001-BR-21** (Definition) 答疑：就一份文档发起的多轮讨论，用于理解
  其内容并按对话结论修订文档；不是评审动作，适用于任意类型与任意状态，不改变
  文档状态。

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
  Then 恰为 `archived`
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
  Then 恰为 `task`，且新 task 的 `parent` 指向该 plan
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
  Given 答疑的对话得出一条修订结论
  When 答疑收尾
  Then 该结论体现在文档正文中

## Links

- Consumed by: spec-00001-docs-whiteboard
