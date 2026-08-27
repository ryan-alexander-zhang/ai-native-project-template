---
id: decision-00013-rule-notation-not-dmn
type: decision
status: active
---

# Decision: 规则表达不迁 DMN——分类学维持，借 FEEL 写法纪律与链式拆表补精确性

> 裁定 rule 文档维持 Markdown 条目形态与 Definition/Constraint/Decision 三分类，
> 不引入 DMN/FEEL 工具链；以两条写作纪律补足 FEEL 内建而散文易失的精确性：
> 复杂条件用显式记号，大表拆成经命名中间值串联的链式小表。

## 1. 需要做这个决定的原因

`docs/rule/README.md` 的决策表借用了 DMN 的 hit policy（`UNIQUE`/`FIRST`、
otherwise 行、`—` 语义），但"为何不整体采用 DMN"从未成文——评审中这个问题
被再次问起，说明它够格记录。同一轮评审确认了两处实际缺口：

- 四类条件语义 FEEL 在语言层内建、而散文单元格反复产生歧义：区间边界开闭
  （"超过 30 天"）、日历量进位（"满一个月"）、集合量词（"多笔未付"）、数值
  舍入（方向与位置）。验证端 `ACCEPTANCE.md` 已有配套（`FIRST` 表边界两侧、
  omission heuristics 的舍入方向），撰写端无成文口径。
- `docs/spec/README.md` 有 Sizing and Splitting 一节，`docs/rule/README.md`
  没有——决策表膨胀时无既定响应。

## 2. 决定

| # | 做法 | 理由 |
| --- | --- | --- |
| 1 | 分类学维持 Definition / Constraint / Decision（SBVR 的定义性/操作性规则传统），Decision 表继续嵌 DMN 决策表子集 | DMN 只建模决策（输入→推导输出），标准明确不覆盖约束类规则；Constraint（可违反 + 违反响应必填）在 DMN 之外，是 SBVR operative rule 的领地 |
| 2 | 不引入 DMN/FEEL 工具链，Markdown 仍是唯一事实来源 | 无决策引擎消费规则；`.dmn` 为 XML + 图形符号，不可 diff、不可在 git/PR 里评审，破坏 markdown/白板/GWT 流水线（decision-00005 的"不立第二事实来源"） |
| 3 | 新增条件写法纪律：`docs/rule/README.md` 的 Condition notation 一节（区间显式开闭、日历进位入 Terms、量词带谓词与阈值、舍入入 Definition、缺失值有行接住） | 把 FEEL 内建语义里最易歧义的几处变成评审清单，以写作纪律拿到语言强制的大部分收益 |
| 4 | 新增拆表纪律：`docs/rule/README.md` 的 Sizing and Splitting 一节（中间值以 Definition 命名入 Terms、链式小表） | DMN 应对复杂决策靠 DRD 分解，此为其文本等价物；"规则变复杂"因此不构成迁 DMN 的理由 |

## 3. 考虑过的其他选项

| 选项 | 结论与理由 |
| --- | --- |
| **整体迁 DMN**（`.dmn` 文件为事实源，Markdown 降为说明） | **否决**。Constraint 类规则无处安放，只能硬扭成 yes/no 决策并丢失违反响应这一必填项；工具链与交换格式的代价换不来收益——没有引擎执行它 |
| **单元格写可求值的 FEEL 表达式**（文本内嵌真 FEEL） | **否决**。无求值器时它只是更陌生的散文，歧义不减、可读性降；本决定第 3 条以"借写法不借语言"拿到同一精确性 |
| **白板校验单元格内容**（区间记号、UNIQUE 表不重叠/完备性检查） | **暂缓，留作退路**。它扩大 decision-00005 定下的解析诊断边界（行形态 → 单元格内容）。触发条件：出现一张表，其错漏造成真实损失、且行数多到肉眼评审不可靠——届时先评估白板加 `UNIQUE` 重叠检查（表格行已结构化，成本远低于 DMN），再谈迁移 |

## 4. 后果

**接受的代价**

- 精确性仍靠人审与 GWT 兜底：机器不校验单元格内容，`UNIQUE` 表的完备性与
  不重叠无静态检查。缓解：`ACCEPTANCE.md` 的最小集（每行一例、无行命中一例、
  边界两侧）承担语义钉死。
- 评审清单变长一节。

**得到的**

- 四类高频歧义（边界、日历、量词、舍入）第一次有成文口径。
- rule 文件夹补上 sizing 规则，与 spec 对齐。
- "何时才需要 DMN"的判据成文：机器校验或引擎执行的**具体需求出现**时，
  而非"规则变复杂了"。

**不变的**

- 白板条目文法（decision-00005）不变：两条纪律管条目**内容**，不管行形态，
  不进解析诊断。
- `ACCEPTANCE.md` 不变：验证端要求已存在。
- 既有 rule 文档不因此进修订轮：纪律对新写与下一次实质性修订生效。

## 5. 这个决定约束什么

- `docs/rule/README.md` —— Condition notation 与 Sizing and Splitting 两节
  即本决定第 3、4 条的正文。
- `docs/rule/TEMPLATE.md` —— 示例体现区间记号与舍入提示。
- 后续任何「rule 迁 DMN」或「单元格内容进白板诊断」的提案：先出示 §3 第三行
  的触发条件已满足的证据，再谈。
