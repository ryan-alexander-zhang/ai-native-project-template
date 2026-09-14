# Operations

This directory stores operation documents.
Use `TEMPLATE.md` for front matter.

## Must Include

- deployment guides
- runbooks
- troubleshooting
- on-call process
- release process
- SLO definitions — for every `runtime`-stage `quality-<n>-QS-<i>.<k>` the
  doc `implements`: the SLI query, the SLO target (which is that scenario's
  Measure, restated not re-decided), the alert rule, and the error budget policy
  that says what happens when the budget is spent (`QUALITY.md`, Verification
  Axis)

Add more when useful.

## Relations

- `implements` — the `decision` or `design` this procedure carries out, or the
  `quality` doc / `quality-<n>-QS-<i>.<k>` scenarios whose `runtime` verification
  this doc holds. A `runtime` scenario with no `active` operation doc
  implementing it is unverified: a warning while only the quality doc is
  `active`, an error once a `plan` carrying the scenario is `resolved`.

## Exclude

- product requirements
- durable decision records
