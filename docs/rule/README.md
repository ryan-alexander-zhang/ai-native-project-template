# Business Rules

This directory stores business rules.
Use `TEMPLATE.md` for front matter.

## Must Include

- applicability
- terms the rules are built on
- the rules, each numbered `rule-<n>-BR-<i>` and tagged with its kind
- acceptance, numbered `rule-<n>-AC-<i>.<k>`
- open questions — what is still undecided

Add more when useful.

### Rule kinds

Tag every rule. The kind decides what else it must state.

| Kind | States | Must also state |
| --- | --- | --- |
| Definition | how a value is derived | — |
| Constraint | what must never be true | the response when it is violated |
| Decision | which outcome applies to which case | a hit policy, and an otherwise row |

A Definition cannot be violated; it defines. A Constraint can, so a rule that
names no violation response leaves the implementer to invent one.

### Decision tables

1. Hit policy, taken from DMN: `UNIQUE` (exclusive rows, order irrelevant) or
   `FIRST` (ordered, first match wins). Prefer `UNIQUE` — non-overlap is
   checkable, first-match is not.
2. End a `FIRST` table with an explicit otherwise row, numbered like any other.
3. `—` means the column does not participate in that row. Never empty or false.

### Condition notation

Rule text and table cells are prose the whiteboard does not parse; precision is
the author's job, checked at review. Checklist items, not grammar
(`decision-00013-rule-notation-not-dmn`):

1. Ranges use explicit interval notation with stated boundaries — `(30, 60]`,
   `>= 2` — never "over 30" or "about a month".
2. A calendar quantity (month, day) states its carry semantics in Terms before
   a rule uses it (e.g. "one month later: the same day next month; when that
   day does not exist, the last day of that month").
3. A quantified condition over a collection states its predicate and threshold
   ("count of unpaid invoices `>= 2`"), with the predicate's term defined in
   Terms.
4. A derived number states rounding direction, precision, and where rounding
   applies, in its owning Definition.
5. An input that can be missing is decided by some row — in a `UNIQUE` table an
   explicit row, in a `FIRST` table the otherwise row — never left to fall
   through silently.

### Acceptance

Every `BR` needs at least one example; an unreferenced rule is unverified.

## 机器可读形态（条目文法）

白板按以下形态解析本文件夹文档的正文；不合式的行进解析诊断
（`spec-00001-FR-40`，取舍见 `decision-00005-whiteboard-parsing-contract`）：

- 规则声明，二选一，整行起头：
  - 列表项：`- **rule-<n>-BR-<i>** (<Kind>) <正文>`，后续缩进行为续行
  - 决策表行：`| **rule-<n>-BR-<i>** | <单元格>… |`，不支持续行
  - `(<Kind>)` 是约定的推荐标注，缺失暂不进诊断
- 验收标准：`- **rule-<n>-AC-<i>.<k>** (rule-<n>-BR-<i>)` 起头，归属标注必写
  （缺失即进解析诊断的无法归属类）；Given / When / Then 各占续行。
- 只有以**本文档 id** 为前缀的声明才属于本文档；整行引用他文档的条目不构成
  声明，也不进诊断。
- 条目 id 在散文中一律用反引号引用；**粗体 id 是声明专用形态**——整行以粗体
  条目 id 起头而不合上述形态的行进解析诊断。

## Relations

- `informs` — the `spec` / `design` / `plan` docs these rules are input for.

## Exclude

- system behaviour: idempotency, retries, timeouts (use the consuming `spec`)
- where and when a rule is checked, and by which component (use `design/`)
- technical design (use `design/`)
- lessons learned and pitfalls
- task breakdown

## Note

Every rule must be decidable. "appropriately", "where necessary" — not finished.

The test against a system requirement: remove the software. If it still holds,
it is a rule.

## Sizing and Splitting

One rule doc is one policy area, and one table answers one question. Review
triggers, not hard gates — when one fires, decide deliberately instead of
appending by default:

- a table can no longer be read in one pass (as an order of magnitude: past
  ~6 input columns or ~15 rows)
- rows keep multiplying because two independent questions are answered in one
  table

How to split a table: name the intermediate value — define it with a
Definition rule (or its own small table), add it to Terms, and let the
downstream table take it as an input. Chains of small tables through named
intermediate values, not one wide table (DMN's decision-requirements pattern,
in text). Restructuring an `active` rule doc this way is a substantive
revision — it goes through the revision round (`rule-00001-BR-3`).

When the doc itself has grown into two policy areas, split the doc per
`docs/README.md` (`supersedes` + `archived`), like an oversized spec.
