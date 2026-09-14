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
- `verifies` — what was verified: `spec` / `rule` / `quality` ids, or requirement
  ids down to `spec-00001-AC-1.1` / `rule-00001-AC-1.1` / `quality-00001-QS-1.1`
  granularity. It must match the acceptance checklist below.

## Exclude

- long-term rules
- architecture truth
- formal specs

## Acceptance checklist

When a `plan` with `spec`/`rule`/`quality` items in its delivery scope is verified for `resolved`, record acceptance here.
Set `parent` to the plan id; link each row to a requirement/GWT/scenario id:

| GWT / requirement id | Test | Result | Evidence |
| --- | --- | --- | --- |
| spec-00001-AC-5.1 | test_duplicate_webhook_is_noop | pass | ... |
| rule-00001-AC-3.1 | test_late_fee_standard_tier | pass | ... |
| quality-00001-QS-1.1 | k6 `payments-peak.js` | pass | `reports/load/2026-09-14-payments-peak.html`: p99 287 ms, 500 rps, 30 min |
| quality-00001-QS-1.2 | SLO `payments-latency` | pass | `operation-00003-payments-slo` §6; alert `payments-latency-burn` wired in `alerts/payments.yaml` |

List any unfinished or uncovered requirement. A fail/missing row blocks `resolved`.
Every `spec-<n>-FR-<i>`, `rule-<n>-BR-<i>`, and `quality-<n>-QR-<i>` in scope must
appear; an unreferenced rule row is an unverified rule.

A `QS` row's `Test` names what ran; its `Result` is `pass` only per the stage
and method the scenario declares (`QUALITY.md`, Verification Axis). For a
`load`, `chaos`, or `observe` scenario the `Evidence` cell is **required** and
links the report artifact, experiment report, or operation doc plus the alert
rule's location — a test name alone is not evidence that a measure held.

## Machine-readable form (checklist grammar)

The acceptance checklist is parsed in the form below; a row that does not fit
is a parse error:

- A table is an acceptance checklist when its header contains `Test` and
  `Result` (substring match; neither may be the first column) and its first
  column holds **full item / AC / QS ids** (`<type>-<nnnnn>-(FR|BR|QR|AC|QS)-…`).
  Document ids do not count — a table whose first column is document ids (such as an
  evidence table for closed issues) is not read as a checklist.
- An `Evidence` column is optional for `AC` rows and required, non-empty, for a
  `QS` row whose method is `load`, `chaos`, or `observe`.
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
