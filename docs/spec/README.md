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
- links to the `design/` docs it builds
- open questions — what is still undecided

Add more when useful.

## 机器可读形态（条目文法）

白板按以下形态解析本文件夹文档的正文；不合式的行进解析诊断
（`spec-00001-FR-40`，取舍见 `decision-00005-whiteboard-parsing-contract`）：

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
