# Records

Process records and reports. Front matter: `TEMPLATE.md`.

## Must Include

- test reports
- review records
- acceptance records
- retrospectives
- research conclusions

Add more when useful.

## Relations

- `parent` — the `plan` this record accepts. **Required for an acceptance
  record**: the resolved gate counts only rows from records whose `parent` is
  the plan being resolved.
- `verifies` — `spec` / `rule` / `quality` ids, or item ids down to
  `spec-00001-AC-1.1` / `rule-00001-AC-1.1` / `quality-00001-QS-1.1`. Must
  match the checklist below.

## Exclude

- long-term rules
- architecture truth
- formal specs

## Acceptance checklist

When a `plan` with `spec`/`rule`/`quality` items in scope is verified for
`resolved`: `parent` = the plan id; one row per requirement / GWT / scenario id:

| GWT / requirement id | Test | Result | Evidence |
| --- | --- | --- | --- |
| spec-00001-AC-5.1 | test_duplicate_webhook_is_noop | pass | ... |
| rule-00001-AC-3.1 | test_late_fee_standard_tier | pass | ... |
| quality-00001-QS-1.1 | k6 `payments-peak.js` | pass | `reports/load/2026-09-14-payments-peak.html`: p99 287 ms, 500 rps, 30 min |
| quality-00001-QS-1.2 | SLO `payments-latency` | pass | `operation-00003-payments-slo` §6; alert `payments-latency-burn` wired in `alerts/payments.yaml` |

List every unfinished or uncovered requirement; a fail/missing row blocks
`resolved`. Every `spec-<n>-FR-<i>`, `rule-<n>-BR-<i>`, `quality-<n>-QR-<i>` in
scope appears; unreferenced = unverified.

A `QS` row: `Test` names what ran; `Result` is `pass` only per the scenario's
method and stage (`QUALITY.md`, Verification Axis). For `load`, `chaos`, or
`observe`, `Evidence` is **required**: report artifact, experiment report, or
operation doc plus alert rule location. A test name alone is not evidence that
a measure held.

## Machine-readable form (checklist grammar)

A row that does not fit is a parse error:

- A table is a checklist when its header contains `Test` and `Result`
  (substring; neither in the first column) and its first column holds **full
  item / AC / QS ids** (`<type>-<nnnnn>-(FR|BR|QR|AC|QS)-…`). A first column of
  document ids (an evidence table for closed issues) is not a checklist.
- `Evidence`: optional for `AC` rows; required, non-empty, for a `QS` row of
  method `load`, `chaos`, or `observe`.
- First cell = **exactly one** id. No ranges (`AC-2.1 … AC-9.2`), no several
  ids per cell.
- Any other table with item / AC ids in its first column (a revision map) must
  not carry both `Test` and `Result` headers.

## Note

A record once `active` is evidence: never revised. Corrections and later
findings go into a new record (which may `verifies` the same items). No writing
path — manual edit, revision round, co-write — is exempt.
