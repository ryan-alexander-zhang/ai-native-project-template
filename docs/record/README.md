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

## Exclude

- long-term rules
- architecture truth
- formal specs

## Acceptance checklist

When a feature-sized `plan` is verified for `resolved`, record acceptance here.
Set `parent` to the plan id; link each row to a requirement/GWT id:

| GWT / requirement id | Test | Result | Evidence |
| --- | --- | --- | --- |
| us-00001-AC-1.1 | test_open_invoice | pass | ... |

List any unfinished or uncovered requirement. A fail/missing row blocks `resolved`.

## Note

Records are time-based and evidence-based.
