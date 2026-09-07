---
id: decision-90003-doc-freshness
type: decision
status: active
verified_against: 9d3547ce
motivated_by: [analysis-90001-doc-code-drift]
---

# Decision: living docs 携带 `verified_against`，过期由 trace-check 派生为 STALE

> 每份 `active` 的 spec / rule / design / decision 在 front matter 记录上次确认其仍为真时的 commit；`scripts/trace-check` 数出此后触及其相关代码的提交数，超阈值报告 STALE，plan 转 `resolved` 时其 `implements` 的文档若 STALE 即失败。不新增 status 值。

## 1. 需要做这个决定的原因

- 状态词表只有 `draft → active → archived`，没有「仍 active 但已不再为真」的表达；`archived` 要求先有替代文档（`docs/README.md:23,26`）。文档过期没有任何信号（`analysis-90001-doc-code-drift` §2、§3 第 7 条）。
- ADR 的公认失败模式是「superseded 靠人记得改状态字段，实践中没人改」；任何要人手工维护的过期标记会重蹈覆辙。
- 本仓库自己的旧 ARCHITECTURE.md 五个月无人发现是占位符，直到 `a35d0d67` 才删除。
- decision 的 `constrains` 回填只有 prose（`docs/decision/README.md:28-31`），新 spec 落在 active decision 下却未被列入，无人标记。

## 2. 决定

| # | 做法 | 理由 |
| --- | --- | --- |
| 1 | `spec` / `rule` / `design` / `decision` 转 `active` 时写入 `verified_against: <commit sha1 前 8 位>`，值为该次确认时的 HEAD。修订轮重新接受时更新；一次显式的「重读确认」也可只更新此字段并提交。 | 一个字段，由转 active 的动作顺手写，不需要额外记忆。 |
| 2 | 文档的「相关代码」由已有锚点推导：spec / rule 为其 AC 的携带测试所在文件；design 为 §中以 `anchor: <path>` 标注的模块路径（本决定新增该写法，见 4）；decision 为其 `constrains` 所列文档的相关代码之并集。 | 复用 decision-90001 的后缀与 decision-90002 的路径，不引入新的绑定机制。 |
| 3 | `scripts/trace-check` 新增检查 (k)：对每份 `active` 且有 `verified_against` 的文档，统计 `verified_against..HEAD` 中触及其相关代码的提交数；超过阈值（写死为 10，不加配置文件）即打印 `STALE <path> verified_against <sha> N commits since touched <paths>`。STALE 只报告。无 `verified_against` 的 active 文档报告 `UNVERIFIED-AGE`，同样只报告。 | 报告不失败，避免变成没人理的红灯；失败点放在下一条。 |
| 4 | 检查 (k) 的失败点：`resolved` plan 的 `implements` 所指文档若 STALE，失败；`design` TEMPLATE 增加 `anchor: <repo-relative path>` 行的写法说明，放在描述具体模块的小节首行。 | 只在有人声称「做完了」时强制，与 (d)(g) 同一原则。 |
| 5 | 检查 (l)：一份 `active` spec / rule / design / plan 的正文或 front matter 引用某 `active` decision 的 id，而该 decision 的 `constrains` 未列出它且它也未声明 `implements` 该 decision，报告 `UNBOUND`。只报告。 | 把 `docs/decision/README.md:28-31` 的回填要求从 prose 变成可见。 |
| 6 | 不新增 `stale` status 值。 | stale 是脚本每次派生的事实，不是需要人流转的状态；做成 status 就回到了 ADR 的失败模式。 |

## 3. 考虑过的其他选项

| 选项 | 结论与理由 |
| --- | --- |
| 新增 `stale` 为第四个 status | **否决**。要人记得改字段，正是要避免的。 |
| 用 `reviewed: <date>` 日期加固定复审周期（g3doc 形态） | **否决**。日期与代码变化无关，安静的模块被误报、活跃的模块漏报；commit 计数直接对应变化量。 |
| 用 trace-check 算出所有变化即失败 | **否决**。文档相关代码只要有提交就红灯，等于禁止改代码。阈值加只在 resolved 时失败是折中。 |
| 阈值可配置 | **否决**。首个版本写死 10；需要调整时改脚本并记录到本决定。 |
| 相关代码由人在 front matter 手写路径列表 | **否决**。又一份要维护的清单；已有锚点足够推导。 |

## 4. 后果

**接受的代价**

- 四类 living doc 多一个字段；转 active 的动作多一步。
- 无锚点的文档（没有携带测试的 spec、没有 anchor 的 design）报不出 STALE，只能报 UNVERIFIED-AGE；这是诚实的缺口，不假装能算。
- `git log` 计数让 trace-check 从纯文件扫描变为需要 git 历史；浅克隆下 (k) 退化为报告「history unavailable」。

**得到的**

- 「文档过期」第一次有信号，且不靠人记得。
- decision 的约束范围可见，缺口 7 关闭一半。

**不变的**

- status 词表、修订轮、record 不可变，全部不变。
- `archived` 的语义不变。

## 5. 这个决定约束什么

- `docs/README.md` Front Matter Rules：`verified_against` 字段的定义与写入时机。
- `docs/{spec,rule,design,decision}/TEMPLATE.md`：字段；`docs/design/TEMPLATE.md` 与 `README.md`：`anchor:` 写法。
- `DOCUMENT.md` Status Workflow：转 active 时写入 / 更新该字段。
- `whiteboard.config.yaml`：`verified_against` 非关系字段，白板需忽略；`carries:` 不变。
- `scripts/trace-check`、`scripts/test_trace_check.py`：检查 (k)(l)。
- `CONTEXT.md`：Stale、Unbound 词条。
