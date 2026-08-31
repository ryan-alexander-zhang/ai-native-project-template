---
id: report-00004-docs-conformance-audit
type: report
status: active
---

# Report: 存量文档对现行 docs 体系的合规排查

> 2026-08-31 合入 main 的体系演进后，对本分支全部 270 份实例文档的逐份
> 排查：机器可读契约近乎干净，内容契约全面落后于现行模板——本报告是
> [decision-00022](../decision/decision-00022-legacy-docs-debt-policy.md)
> 立债方针的债务清单。

## 1. Summary

270 份无一完全符合现行 README/TEMPLATE。机器层（id、status 词汇、
front matter 字段集、引用完整性、条目文法）在本轮确定性修复后零诊断、
零异常；缺口集中在内容契约——模板骨架、Issue 三件套、验收最小集、
record 验收链——处置方针见 decision-00022（立债不翻新、新档从严、
触碰即修）。

## 2. Scope and Method

- Covers: 分支 lang/java/ddd 的 docs/ 全部实例文档（270 份，2026-08-31
  合并 commit 之后的树）。
- Method: 白板解析器全量机器扫（/api/graph 的诊断与异常）+ 四路子代理
  按文件夹对照各自 README/TEMPLATE 逐份人审（spec/design/decision 32、
  analysis 42、issue 169、plan/record/report/reference/operation 27）。

## 3. Findings（债务清单）

**已随本轮修复、不入债**：reference 时间戳 id 迁规范路径改号、非法
`role` 字段、`decision-00010` 撞号改 `decision-00021`、4 处死链、
29 份 analysis 的 `informs`→`parent`、design→decision 的 `informs`
越界反转为 decision 侧 `constrains`、正文已点名的 `motivated_by`/
`constrains` 回填、EARS 非词表标注、AC 编号与归属错位、spec-00002
文末垃圾行、全分支 wikilink 转相对链接、OQ 违例文档降回 draft。

**立债项（触碰即修）**：

1. **模板骨架缺失**：42/42 analysis 无 `Question and Method / Findings /
   Conclusion` 骨架（00014–00042 无 Method，不可复现）；17/17 decision
   仍是旧 ADR 五节（后果三段式 0/17、§5 约束节 0/17）；169/169 issue
   缺编号小节骨架；各类 H1 前缀与一句话导语普遍缺失。
2. **Issue 内容三件套**：`Introduced by` 0/169、同因扫描 7/169、
   Follow-through 三项约 2/169；`blocks` 字段自 issue-00128 起连续
   44 份缺失；33 份全文无 file:line 证据。
3. **spec 验收缺口**：spec-00001/00002 低于 ACCEPTANCE.md 最小集
   （Ubiquitous 缺 edge、Optional 缺 absent 侧、Event 缺拒收态等，
   逐条见两份 spec 正文对照）；多行为 AC 待拆；两份 spec 均无
   Business Rules 表与 rule 链接（docs/rule/ 尚无实例）；spec-00002
   的 Stories 无交付切片。
4. **越界内容**：多份 analysis/design 混入 decision/plan/spec 的内容
   （落地建议、分阶段计划、验收矩阵、已定决策），见各文档正文；
   decision-00016 为「增补」型补篇，应并回 decision-00013 或独立成题。
5. **record 验收链**：10 份 resolved 的 plan 无合规验收 record
   （plan-00001/00005/00006/00007/00008/00010/00012/00013/00014/00015）；
   record-00001 缺 `verifies`、清单首列非条目 id、`parent` 与所验内容
   不对口（照「record 即证据不回改」留史）。
6. **零散**：plan-00001 缺必填 `implements`；plan-00003/00009/00014 缺
   必备章节；plan-00011 交付范围未申报 spec-00002；operation-00001 缺
   验证与回滚节、排查表缺行动列；report-00001 缺 Method；8 份 reference
   无逐字摘录。

## 4. Recommendations

- 方针与约束见 decision-00022：存量立债、新档从严、触碰即修——任何
  文档进修订轮（含共写、澄清、审计会话）时，顺带补齐其在本清单中的
  欠项后方可回 active。
- 债务收敛不设专项 plan；以本报告为对账底册，逐步清零后归档本报告。
