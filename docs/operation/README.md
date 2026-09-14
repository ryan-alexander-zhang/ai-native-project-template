# Operations

Operation documents. Front matter: `TEMPLATE.md`.

## Must Include

- deployment guides
- runbooks
- troubleshooting
- on-call process
- release process
- SLO definitions — per `runtime` `quality-<n>-QS-<i>.<k>` the doc
  `implements`: SLI query, SLO target (= the scenario's Measure, restated not
  re-decided), alert rule, error budget policy (`QUALITY.md`, Verification Axis)

Add more when useful.

## Relations

- `implements` — the `decision` or `design` this procedure carries out, or the
  `quality` doc / `quality-<n>-QS-<i>.<k>` scenarios whose `runtime`
  verification this doc holds. A `runtime` scenario without one is unverified:
  warning while only the quality doc is `active`, error once a `plan` carrying
  it is `resolved`.

## Exclude

- product requirements
- durable decision records
