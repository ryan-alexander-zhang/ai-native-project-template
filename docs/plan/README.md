# Plans

Implementation plans. Front matter: `TEMPLATE.md`.

## Must Include

- Design — links to the [`design/`](../design/README.md) docs this plan builds;
  never inline.
- Tasks — a task adding a capability names its source: `reuse:` the repo path,
  framework artifact, or library, or `build:` with the `decision` that rejects
  reuse. A new library needs its own `decision`.
- Detailed Acceptance Path

Add more when useful.

## Relations

- `implements` — **required**: the `spec`, `rule`, `quality`, and/or `design`
  this plan makes real, or the `report` a remediation plan works through. Item
  ids (`spec-<n>-FR-<i>` / `rule-<n>-BR-<i>` / `quality-<n>-QR-<i>`) declare
  the **delivery scope**; a doc id puts every item in scope. Prefer item ids for
  a slice of a larger doc. A feature plan takes into scope the `QS` ids its
  spec's §7 cites.
- To reach `resolved`, a plan with `spec`/`rule`/`quality` items in scope needs
  a [`record`](../record/README.md) with `parent` = this plan and `verifies`
  listing the ids checked. `open → resolved` is refused while any AC or QS in
  scope lacks a `pass` row, a `load` / `chaos` / `observe` QS row has empty
  Evidence, or a `runtime` QS has no `active` `operation` doc implementing it.

## Exclude

- pure product requirements

## Guideline

1. Tasks cohesive, low dependency, parallel when possible.
2. Acceptance covers both: every task done, and the feature tested against the target need.

## Note

A plan is a work item: status vocabulary per [docs/README.md](../README.md).
