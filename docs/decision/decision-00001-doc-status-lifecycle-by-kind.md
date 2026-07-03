---
id: decision-00001-doc-status-lifecycle-by-kind
type: decision
role: main
status: active
parent:
---

# Split document status by document kind

## Context

The original template used one status vocabulary for every document:
`draft|active|archived`.

That works for living documents such as PRDs, specs, decisions, and design
docs, where status describes whether the file is the current source of truth.
It does not work as well for work items such as issues, plans, and tasks,
where contributors need to know whether the work is still open, completed, or
deliberately abandoned.

Using `archived` to mean "done" creates ambiguity: a file can be superseded
without the work being done, and work can be completed without the file being
superseded.

## Decision

Use two status sub-vocabularies:

- Living docs (`spec`, `design`, `decision`, `prd`, `idea`, `analysis`,
  `integration`, `reference`, `user-story`, `memory`, `operation`, `record`,
  `prompt`, `report`): `draft -> active -> archived`.
- Work items (`issue`, `plan`, `task`): `draft -> open -> resolved`, with
  terminal alternatives `wontfix` and `archived`.

`archived` means "this file is no longer the live source". It is not a synonym
for "done". Work completion is recorded with `resolved`; deliberate non-action
or invalidated work is recorded with `wontfix`.

## Consequences

- `docs/README.md` defines the full status vocabulary and the per-kind rules.
- `docs/issue/`, `docs/plan/`, and `docs/task/` templates use the work-item
  vocabulary.
- Living document templates keep `draft|active|archived`.
