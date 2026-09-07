---
id: decision-90002-architecture-assertions
type: decision
status: active
verified_against: 4f233b24
motivated_by: [analysis-90001-doc-code-drift]
---

# Decision: ARCHITECTURE.md 的每条边界配一条构建时运行的断言

> ARCHITECTURE.md §5 的每条依赖方向或模块边界规则必须指名一个让构建失败的可执行断言（架构测试或依赖 lint 配置），文档只负责解释；`scripts/trace-check` 核对被指名的文件存在。取代「边界只写在 prose 里、初始填写一次、之后无触发」的现状。

## 1. 需要做这个决定的原因

- `ARCHITECTURE.md` 只有两条约束：首个 plan 开始前从模板填写（`AGENTS.md:89-94`），以及「只链 active 文档」一句 prose（`ARCHITECTURE_TEMPLATE.md:8`）。模块边界变了没有任何触发（`analysis-90001-doc-code-drift` §3 第 4 条）。
- 本仓库自己的 `ARCHITECTURE.md` 直到 `a35d0d67` 之前一直是 5 月的旧版占位符，与 9 月的 arc42 模板形态都不一致，五个月无人发现——prose 架构文档腐烂的实证。
- 业界对应做法成熟：ArchUnit / ArchUnitTS / dependency-cruiser / arch-go / import-linter 把「A 层不得依赖 B 层」写成每次构建都跑的断言（fitness function）；ADR 与架构文档的公认失败模式是「状态和边界靠人记得改」（`analysis-90001-doc-code-drift` §2）。

## 2. 决定

| # | 做法 | 理由 |
| --- | --- | --- |
| 1 | `ARCHITECTURE_TEMPLATE.md` §5 建筑块视图新增「Boundaries」表：`| Rule | Enforced by |`。每条依赖方向、分层或模块可见性规则占一行，`Enforced by` 填让构建失败的断言文件或 lint 配置的仓库相对路径；不能强制的行写 `Unenforced: <why>`，使缺失成为决定而非遗漏。 | 断言是事实，文档是解释；表把两者一行对一行绑住，与 spec 里 FR 必须有 AC 的模式同构。 |
| 2 | 断言工具由各 lang 分支在 `CODE_QUALITY.md` §2 gates 表新增的「Architecture」行选定并登记；模板层不指定工具，只给候选：Java ArchUnit、TS/JS dependency-cruiser 或 ArchUnitTS、Go arch-go / go-arch-lint、Python import-linter。 | 工具与语言强绑定，属 lang 分支自定义范围（`.template-sync.json` note）。 |
| 3 | `DEVELOPMENT.md` Development Matrix 与 `TESTING.md` Testing Matrix 各加一行「Module boundary or dependency-direction change → update the architecture assertion and `ARCHITECTURE.md` §5 Boundaries together」。 | 让「改边界」有明确的最低要求，与现有「Behavior change → 实现、测试、文档一起改」同款。 |
| 4 | `scripts/trace-check` 新增检查 (j)：若 `ARCHITECTURE.md` 存在，解析 §5 Boundaries 表，`Enforced by` 列每个路径必须是受跟踪文件，否则失败；`Unenforced:` 行只报告计数。 | 至少让「断言文件被删或改名」不会静默；断言本身是否仍在跑由 CI 保证。 |
| 5 | `AGENTS.md` §8「首个 plan 开始前填写 ARCHITECTURE.md」不变，但填写完成的标准加一句：§5 Boundaries 表每行有 `Enforced by` 或 `Unenforced:`。 | 出厂即有表，避免下游再次出现只有目录树的占位符。 |

## 3. 考虑过的其他选项

| 选项 | 结论与理由 |
| --- | --- |
| 继续只在 ARCHITECTURE.md 里用 prose 描述边界 | **否决**。本仓库自己五个月没发现占位符，证明 prose 无自检能力。 |
| 从代码生成 C4 图（Structurizr / DSL）替代手写 §5 | **否决为本决定范围**。生成的是「现状」不是「应当」，不能表达被违反的边界；可作为后续叠加。 |
| 用 LLM 判官在 PR 上比对 ARCHITECTURE.md 与 diff | **否决为唯一手段**。非确定、无法做硬门禁；边界是少数能用确定性断言表达的架构事实，不该退化成模型判断。 |
| 模板层统一指定一个跨语言工具 | **否决**。没有一个工具覆盖所有 lang 分支；强行统一等于让部分分支的表全是 `Unenforced`。 |
| trace-check 解析并运行断言 | **否决**。运行断言是各语言测试命令的事（`TESTING.md`），trace-check 只做与语言无关的存在性核对，保持零依赖。 |

## 4. 后果

**接受的代价**

- 每个 lang 分支多一个依赖与一个 gate 配置；`CODE_QUALITY.md` §2 多一行要填。
- 断言写得过细会让重构先改断言再改代码；缓解：表只收「依赖方向与可见性」这类结构规则，不收命名或粒度偏好。
- 断言工具的 allow 列表可能堆积例外；`CODE_QUALITY.md` §6「Resolve → Tune → Suppress」的优先级同样适用于它。

**得到的**

- 边界变化第一次让构建红灯而不是让文档慢慢过期；ARCHITECTURE.md §5 从「可能说谎的事实」变成「指向断言的索引」。
- 下游项目出厂即有 Boundaries 表的空壳，缺口 4「出厂即占位符」被表格结构本身堵住一半。

**不变的**

- arc42 十二节结构与「摘要加链接」原则不变；§5 只是多一张表。
- `ARCHITECTURE.md` 仍是 lang 分支自定义文件、仍被 `template.json` 排除在模板仓库之外。
- 运行时行为、部署、安全等其他章节不引入断言要求。

## 5. 这个决定约束什么

- `ARCHITECTURE_TEMPLATE.md` §5：Boundaries 表及其填写规则。
- `CODE_QUALITY.md` §2：Architecture gate 行。
- `DEVELOPMENT.md` Development Matrix、`TESTING.md` Testing Matrix：边界变更行。
- `AGENTS.md` §8：ARCHITECTURE.md 填写完成的标准。
- `scripts/trace-check`、`scripts/test_trace_check.py`：检查 (j)。
- 后续任何 lang 分支的 `CODE_QUALITY.md` §2 Architecture 行为空即视为违反本决定；引入新断言载体（如生成式 C4）的 decision 须回填到本决定的 `constrains`。
