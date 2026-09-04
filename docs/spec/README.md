# Specs

This directory stores feature specs.
Use `TEMPLATE.md` for front matter.

## Must Include

- context aligned to `CONTEXT.md`
- story slices, each naming the ids it delivers
- system requirements, EARS numbered `spec-<n>-FR-<i>`, acceptance
  `spec-<n>-AC-<i>.<k>`; error and rejection behaviour is an Unwanted requirement,
  not a table
- acceptance for every requirement: each `spec-<n>-FR-<i>` needs at least one
  `spec-<n>-AC-<i>.<k>`; an `FR` no acceptance references is unverified
- links to the `rule/` docs the feature obeys
- links to the `design/` docs it builds. Required before the spec turns `active`
  whenever any `FR` introduces or changes structure that outlives one `plan` — a
  module or boundary, a data model, an API or file-format contract, a state
  lifecycle, a cross-component interaction. When none does, say so in one line
  (`No design: <why>`) so the omission is a decision, not a gap
- open questions — what is still undecided

Add more when useful.

## 机器可读形态（条目文法）

白板按以下形态解析本文件夹文档的正文；不合式的行进解析诊断：

- 需求条目声明，二选一，整行起头：
  - 列表项：`- **spec-<n>-FR-<i>** (<EARS 类型>) <正文>`，后续缩进行为续行
  - 决策表行：`| **spec-<n>-FR-<i>** | <单元格>… |`，不支持续行
  - `(<EARS 类型>)` 是约定的推荐标注，缺失暂不进诊断
- 验收标准：`- **spec-<n>-AC-<i>.<k>** (spec-<n>-FR-<i>)` 起头，归属标注必写
  （缺失即进解析诊断的无法归属类）；Given / When / Then 各占续行。
- 只有以**本文档 id** 为前缀的声明才属于本文档；整行引用他文档的条目不构成
  声明，也不进诊断。
- 条目 id 在散文中一律用反引号引用；**粗体 id 是声明专用形态**——**整行以
  粗体条目 id 起头**而不合上述形态的行会进解析诊断（行中的粗体 id 不受影响，
  但约定仍是散文用反引号）。

## Relations

- `parent` — a `prd`, an `idea`, or empty when the spec is itself the entry point.
- The `plan` declares `implements: [<this spec>]`; the `design` and the `rule`
  declare `informs: [<this spec>]`.

## Exclude

- business rules of any size (use `rule/`)
- implementation shape of any size or kind (use `design/`)
- long product background (use `prd/`)
- task breakdown (use `plan/` or `task/`)
- process reports (use `record/`)

## Note

A spec is one feature — a coherent, shippable capability delivered as one
increment. It holds the requirements and their acceptance, and links to
everything else.

## Sizing and Splitting

The one-feature definition above is also a size rule. These are review
triggers, not hard gates — but when one fires, decide deliberately instead of
appending by default:

- a new revision round mostly **adds** requirements instead of amending
  existing ones — the "feature" has become a product area
- an auditor can no longer read the whole spec in one pass (as an order of
  magnitude: past ~20 FRs or ~500 body lines)
- the Stories table no longer describes one increment

How to split:

1. **New capability → new spec.** Grow sideways, not downward: open a new spec
   with the same `parent` (prd/idea) instead of appending FRs to an existing
   `active` spec. This is the default.
2. **Decomposing an oversized spec.** Write the replacement specs, each
   carrying `supersedes: [<old spec id>]`; set the old spec to `archived`
   Requirement ids are namespaced by doc
   id, so they are **not** renumbered or migrated: existing `record` rows and
   `plan` scopes keep pointing at the archived spec's items, which remain
   resolvable — history is evidence, not content to rewrite. New work cites
   the new specs' items.
3. Never split by moving FRs between two live specs — that breaks every
   acceptance row and delivery scope pointing at the moved ids.
