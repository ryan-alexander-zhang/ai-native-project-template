# Plans

This directory stores implementation plans.
Use `TEMPLATE.md` for front matter.

## Must Include

- Design — links to the [`design/`](../design/README.md) docs this plan builds.
  The design itself lives there, never inline here.
- Tasks — a task that adds a capability names its source: `reuse:` the repo
  path, framework artifact, or library that supplies it, or `build:` with the
  `decision` that rejects reuse. A new library also needs its own `decision`.
- Detailed Acceptance Path

Add more when useful.

## Relations

- `implements` — **required**: the `spec`, `rule`, `quality`, and/or `design` this
  plan makes real, or the `report` whose findings a remediation plan works through.
  It is the only thing tying a plan to what it builds. Requirement-item ids
  (`spec-<n>-FR-<i>` / `rule-<n>-BR-<i>` / `quality-<n>-QR-<i>`) declare the
  plan's **delivery scope**; a whole spec/rule/quality doc id puts every item of
  that doc in scope. Prefer item ids when the plan delivers a slice of a larger
  doc. A plan that builds a feature takes into scope the `QS` ids its spec's §7
  cites — a feature is not delivered while the scenarios it must hold are unproven.
- A plan whose `implements` puts `spec`/`rule`/`quality` items in scope needs, to reach `resolved`, a [`record`](../record/README.md)
  whose `parent` points at this plan and whose `verifies` lists the requirement
  ids it checked. `open → resolved` is refused while any AC or QS of any item in
  the delivery scope lacks a `pass` row in such a record, while a `load` /
  `chaos` / `observe` QS row has an empty Evidence cell, or while a `runtime`
  QS in scope has no `active` `operation` doc implementing it.

## Exclude

- pure product requirements

## Guideline

1. Keep tasks cohesive and low dependency. Tasks should be parallel when possible.
2. Acceptance should cover both: all split tasks are done, and the planned feature is tested and meets the target need.

## Note

A plan is a work item and uses the work-item status vocabulary. See
[docs/README.md](../README.md) for the shared definition.
