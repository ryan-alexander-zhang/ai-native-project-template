---
id: decision-00014-entry-type-birth-paths
type: decision
status: active
constrains: [rule-00001-docs-workflow]
---

# Decision: 无固定上游的六种类型入 BR-26 入口清单——补齐文档出生通路

> 裁定 `decision`、`integration`、`reference`、`operation`、`prompt`、
> `report` 六种类型加入 `rule-00001-BR-26` 的流程入口类型清单，可直接新建；
> 否决为它们另设推进来源。

## 1. 需要做这个决定的原因

`rule-00001` 只给文档两条出生通路：入口类型**新建**（BR-26，原为 idea /
prd / design / analysis 四种），或经产品流**推进**产生（BR-13…BR-17）。
两条通路的类型取并集，16 种类型里有 6 种不在其中——`decision`、
`integration`、`reference`、`operation`、`prompt`、`report` 既非入口、也
不是任何类型的下一步候选。按 BR-27 的字面，它们的新建应被拒绝；而实践中
它们一直存在（decision 已有 13 份），`docs/README.md` 还明文要求为有真实
trade-off 的选择写 decision record。规则、上位文档与实践三者不一致，由
decision-00013 的评审对话暴露。

## 2. 决定

| # | 做法 | 理由 |
| --- | --- | --- |
| 1 | 六种类型加入 BR-26 入口清单，与 design / analysis 同列 | 入选谓词取可核查的一条：这八种在产品流（BR-13…BR-17）中不是任何类型的下一步候选，无推进通路可走，入口是它们唯一的出生方式。decision 以 `motivated_by` 回指来源，无来源可指时省略（来源可以是一次评审对话——本决定连带把该口径写入 decision 文件夹 README 与 TEMPLATE）；operation 的 `implements` 虽指向 decision/design，但二者均无下一步候选（BR-17），同样无法经推进产生 |
| 2 | `record` 不入清单 | record 是 plan 的过程证据，`parent` 指向 plan 是 BR-25 resolved 门的取证前提，经 BR-16 推进产生是正确建模 |
| 3 | 落地为 `whiteboard.config.yaml` 的 `entry` 列表扩充 | 入口集由流程配置承载（`spec-00001-FR-53`），代码只读配置——spec 与代码均无需改动 |

## 3. 考虑过的其他选项

| 选项 | 结论与理由 |
| --- | --- |
| **为六种类型各设推进来源**（如任意类型 → decision，携带 `motivated_by`） | **否决**。decision 恰恰没有固定上游——它由评审、对话、取舍触发，强指一个源文档是伪造血缘；「decision 不推进出任何东西、只作证据链挂进图」的既有建模（BR-17 otherwise）是对的，出生问题不该靠扭曲流向解决 |
| **维持现状**（这六种绕过白板、在 git 里手写出现） | **否决**。BR-27 的字面拒绝与实践长期矛盾，规则失去可判定性；白板的新建入口也无法服务这些类型 |

## 4. 后果

**接受的代价**

- 顶栏新建入口的类型清单变长。缓解：`entry` 是配置，不用某文件夹的项目
  照配置注释既有约定自行删减。
- decision 可在板上新建为 `draft`，但不在可澄清（BR-20）与可审计
  （BR-23）集合内，唯一评审动作是接收。域主本轮裁定不扩澄清集；是否把
  decision 加入可澄清类型留待下一次 BR-20 修订（届时需连带
  `clarifyRules.ts` 的清单与流程配置的 `focus` 行）。

**得到的**

- 16 种类型全部有合法出生通路，BR-26 ∪ BR-13…17 覆盖全集，BR-27 恢复可
  判定。
- 入口/非入口的分界第一次成为干净的二分：携带指回来源关系的（spec、
  rule、plan、task、issue、record）经推进产生，无固定上游的直接新建。

**不变的**

- 产品流下一步表（BR-13…BR-17）不变；decision 仍无下一步。
- BR-27 原文不变——非入口类型的新建仍被拒绝，只是非入口集合缩小。
- `spec-00001-FR-53` 与白板代码不变——入口集经配置生效。

## 5. 这个决定约束什么

- [rule-00001-docs-workflow](../rule/rule-00001-docs-workflow.md) ——
  BR-26 的入口清单与 AC-26.3 由本决定产生。
- `whiteboard.config.yaml` —— `entry` 列表含此十种类型（项目可按配置注释
  删减 situational 文件夹的类型）。
- `CONTEXT.md` ——「流程入口类型」词条随本决定更新。
