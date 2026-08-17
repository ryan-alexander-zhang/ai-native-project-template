# Records

This directory stores process records and reports.
Use `TEMPLATE.md` for front matter.

## Must Include

- test reports
- review records
- acceptance records
- retrospectives
- research conclusions

Add more when useful.

## Relations

- `parent` — the `plan` this record accepts.
- `verifies` — what was verified: `spec` / `rule` ids, or requirement ids down to
  `spec-00001-AC-1.1` / `rule-00001-AC-1.1` granularity. It must match the
  acceptance checklist below.

## Exclude

- long-term rules
- architecture truth
- formal specs

## Acceptance checklist

When a feature-sized `plan` is verified for `resolved`, record acceptance here.
Set `parent` to the plan id; link each row to a requirement/GWT id:

| GWT / requirement id | Test | Result | Evidence |
| --- | --- | --- | --- |
| spec-00001-AC-5.1 | test_duplicate_webhook_is_noop | pass | ... |
| rule-00001-AC-3.1 | test_late_fee_standard_tier | pass | ... |

List any unfinished or uncovered requirement. A fail/missing row blocks `resolved`.
Every `spec-<n>-FR-<i>` and every `rule-<n>-BR-<i>` in scope must appear; an
unreferenced rule row is an unverified rule.

## 机器可读形态（条目文法）

白板按以下形态解析验收清单；不合式的行进解析诊断（`spec-00001-FR-40`，取舍见
`decision-00005-whiteboard-parsing-contract`）：

- 验收清单表的识别：表头含「测试/Test」与「结果/Result」字样（子串即可，两列
  都不得是首列），且首列单元格是**条目/AC id 的全匹配**
  （`<type>-<五位数>-(FR|BR|AC)-…`）。文档 id 不算——首列是文档 id 的表格
  （如「缺陷关闭的证据」表）不会被当作验收清单。
- 「Evidence/证据」列可有可无。
- 验收行的首列为被验 id：**恰一个**。禁止区间写法（`AC-2.1 … AC-9.2`）与一格
  多 id——每行一个 id，逐条可核对。
- 其它含条目/AC id 首列的表格（修订对照表等）不得同时含测试与结果表头，否则
  会被当作验收清单解析。

## Note

Records are time-based and evidence-based.
