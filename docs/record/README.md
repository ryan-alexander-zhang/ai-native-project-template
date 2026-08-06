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

## Note

Records are time-based and evidence-based.
