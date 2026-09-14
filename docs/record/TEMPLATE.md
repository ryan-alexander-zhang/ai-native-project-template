---
id: record-00001-example-slug
type: record
status: draft|active|archived
parent: <plan-id>                             # the plan this record accepts
verifies: [<spec-id | rule-id | quality-id | requirement-id>, ...]   # what this record verified
---

# Acceptance Record: <what was accepted>

Acceptance of [<plan-id>](../plan/<plan-id>.md). <One sentence each: scope decisions,
out-of-scope items also verified, root of the test paths.>

<!--
`parent` is REQUIRED when this record is a plan's acceptance record: the
resolved gate only counts rows from records whose `parent`
points at the plan being resolved. A record without it is invisible to the gate.
`verifies` must match the checklist below.
-->

## Quality Gates

- `<test command>`：<files / tests, all passing>
- `<typecheck command>`：<result>
- `<coverage command>`：<the four numbers against the threshold; state that no
  threshold was adjusted>

## Acceptance Checklist

<!--
Machine-readable form (docs/record/README.md, "Machine-readable form"):
- the header must contain `Test` and `Result` (substring match), and
  neither may be the first column;
- the first column is EXACTLY ONE item/AC/QS id, full-matched
  (`<type>-<nnnnn>-(FR|BR|QR|AC|QS)-…`). No ranges (`AC-2.1 … AC-9.2`), no several
  ids in one cell — one id per row, so every row is checkable on its own;
- the `Evidence` column is optional for AC rows; for a QS row whose method is
  load / chaos / observe it is required and links the report or operation doc;
- any other table whose first column holds item/AC ids must NOT also carry
  Test and Result headers, or it will be parsed as an acceptance checklist.
-->

| GWT / requirement id | Test | Result | Evidence |
| --- | --- | --- | --- |
| <spec-00001-AC-1.1> | <test name (path)> | pass | <optional> |
| <rule-00001-AC-1.1> | <test name (path)> | pass | <optional> |
| <quality-00001-QS-1.1> | <load script / experiment / SLO name> | pass | <report artifact, or operation doc + alert location — required> |

<Name every uncovered or failing item. A fail/missing row blocks `resolved`.>

## Implementation Notes

- <the call made while implementing, and why — one line each>
