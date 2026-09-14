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

- `parent` — the `plan` this record accepts. **Required when the record is a
  plan's acceptance record**: the resolved gate only counts
  rows from records whose `parent` points at the plan being resolved — a record
  without it is invisible to the gate.
- `verifies` — what was verified: `spec` / `rule` ids, or requirement ids down to
  `spec-00001-AC-1.1` / `rule-00001-AC-1.1` granularity. It must match the
  acceptance checklist below.

## Exclude

- long-term rules
- architecture truth
- formal specs

## Acceptance checklist

When a `plan` with `spec`/`rule` items in its delivery scope is verified for `resolved`, record acceptance here.
Set `parent` to the plan id; link each row to a requirement/GWT id:

| GWT / requirement id | Test | Result | Evidence |
| --- | --- | --- | --- |
| spec-00001-AC-5.1 | test_duplicate_webhook_is_noop | pass | ... |
| rule-00001-AC-3.1 | test_late_fee_standard_tier | pass | ... |

List any unfinished or uncovered requirement. A fail/missing row blocks `resolved`.
Every `spec-<n>-FR-<i>` and every `rule-<n>-BR-<i>` in scope must appear; an
unreferenced rule row is an unverified rule.

## Machine-readable form (checklist grammar)

The acceptance checklist is parsed in the form below; a row that does not fit
is a parse error:

- A table is an acceptance checklist when its header contains `Test` and
  `Result` (substring match; neither may be the first column) and its first
  column holds **full item / AC ids** (`<type>-<nnnnn>-(FR|BR|AC)-…`). Document
  ids do not count — a table whose first column is document ids (such as an
  evidence table for closed issues) is not read as a checklist.
- An `Evidence` column is optional.
- The first cell of a row is the id verified: **exactly one**. No ranges
  (`AC-2.1 … AC-9.2`) and no several ids in one cell — one id per row, so every
  row can be checked on its own.
- Any other table whose first column holds item / AC ids (a revision map, for
  example) must not also carry `Test` and `Result` headers, or it will be read
  as a checklist.

## Note

Records are time-based and evidence-based. A record, once accepted (`active`),
is evidence: it is not revised — corrections and later findings go into a new
record (which may `verifies` the same items). No writing path — manual edit,
the revision round, or a co-write session — exempts this.
