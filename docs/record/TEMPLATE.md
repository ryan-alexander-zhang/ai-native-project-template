---
id: record-00001-example-slug
type: record
status: draft|active|archived
parent: <plan-id>                             # the plan this record accepts
verifies: [<spec-id | rule-id | requirement-id>, ...]   # what this record verified
---

# 验收记录：<what was accepted>

对 [<plan-id>](../plan/<plan-id>.md) 的验收。<取舍、范围外但一并验收的条目、
测试路径的相对根，各一句。>

<!--
`parent` is REQUIRED when this record is a plan's acceptance record: the
resolved gate (`rule-00001-BR-25`) only counts rows from records whose `parent`
points at the plan being resolved. A record without it is invisible to the gate.
`verifies` must match the checklist below.
-->

## 质量门

- `<test command>`：<files / tests, all passing>
- `<typecheck command>`：<result>
- `<coverage command>`：<the four numbers against the threshold; state that no
  threshold was adjusted>

## 验收清单

<!--
Machine-readable form (docs/record/README.md「机器可读形态（条目文法）」):
- the header must contain 「Test/测试」 and 「Result/结果」 (substring match), and
  neither may be the first column;
- the first column is EXACTLY ONE item/AC id, full-matched
  (`<type>-<五位数>-(FR|BR|AC)-…`). No ranges (`AC-2.1 … AC-9.2`), no several
  ids in one cell — one id per row, so every row is checkable on its own;
- the「Evidence/证据」column is optional;
- any other table whose first column holds item/AC ids must NOT also carry
  Test and Result headers, or it will be parsed as an acceptance checklist.
-->

| GWT / requirement id | Test | Result | Evidence |
| --- | --- | --- | --- |
| <spec-00001-AC-1.1> | <test name (path)> | pass | <optional> |
| <rule-00001-AC-1.1> | <test name (path)> | pass | <optional> |

<Name every uncovered or failing item. A fail/missing row blocks `resolved`.>

## 实现期的既定取舍

- <the call made while implementing, and why — one line each>
