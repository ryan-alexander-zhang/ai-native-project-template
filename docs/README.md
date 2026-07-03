# Docs

This directory stores long-term project documents.

## Front Matter

Every main doc and patch doc should start with:

```md
---
id: <type>-<five-digit-number>-<slug>
type: analysis|decision|design|idea|integration|issue|memory|operation|plan|prd|prompt|record|report|spec|task|us
role: main|patch
status: draft   # start here; promote per kind (see Front Matter Rules below)
parent: <id>
---
```

Write the document description or comment after the front matter.

## Front Matter Rules

- `id` uses `<type>-<five-digit-number>-<slug>`, for example `spec-00001-doc-front-matter`.
- `role: main` is the canonical document for a topic. `role: patch` extends that main document.
- `status` has two sub-vocabularies, by document kind. See [decision-00001](decision/decision-00001-doc-status-lifecycle-by-kind.md).
  - **Living docs** (`spec`, `design`, `decision`, `prd`, `idea`, `analysis`, `integration`, `reference`, `us`, `memory`, `operation`, `record`, `prompt`, `report`): `draft` (work in progress) -> `active` (the current live version / source of truth) -> `archived` (kept for history; no longer the current live version, e.g. superseded by or folded into another doc).
  - **Work items** (`issue`, `plan`, `task`): `draft` (pre-triage) -> `open` (tracked, not yet resolved) -> `resolved` (fix/work applied **and** verified). Terminal alternatives: `wontfix` (deliberately not acting, or the item became invalid / overtaken by events) and `archived` (the *document* was superseded, independent of whether the work was done).
- `archived` is a document-lifecycle state ("this file is no longer the live source"), not a synonym for "done". Record a work item's outcome with `resolved` or `wontfix`, never by archiving it.
- `role: patch` means `parent` is the id of the main document.
- Main document flow is `idea -> prd -> spec -> plan` when the later stage exists.
- A main document in that flow should use the upstream main document id as `parent` when one exists. For example, a main `spec` uses the related `prd` id; a `spec` may instead parent to an `idea`, or have no parent when it is itself the entry point.
- A main `decision` should use the closest upstream main document that created the need for the choice. In this repo that is usually an `idea`, `prd`, or `spec`.
- A patch `decision` is a child of a main `decision`. Its `parent` must be the decision id it extends.
- A main `issue` should use the closest main doc it blocks or clarifies. In this repo that is usually a `task`, `plan`, `spec`, or `prd`.
- `us` (user story) docs own a requirement unit (value statement + EARS requirements + GWT acceptance). Their `parent` is always the `spec` they belong to. Requirement ids carry the doc id, e.g. `us-00001-FR-1` and `us-00001-AC-1.1`.

### When to use `role: patch`

Use `patch` only when the main doc is `active` (locked or already in use) and
the addition is scoped — for example, an FR added after PRD freeze, or a
decision addendum that narrows an existing decision. Otherwise update the
main doc in place. Do not create a patch for routine revisions during `draft`.

## Folders

Each folder is marked **core** (most projects need it) or **situational**
(use only when the project actually calls for it).

- `prd/` — **core** — product requirements
- `spec/` — **core** — feature specs (feature view + technical design)
- `plan/` — **core** — implementation plans
- `decision/` — **core** — durable decision records
- `issue/` — **core** — development issues, fixes, and verification
- `operation/` — **core** — runbook and operations docs
- `memory/` — **core** — reusable long-term knowledge
- `idea/` — **core** — early ideas (some projects skip and start at `prd/`)
- `design/` — situational — durable structural design docs
- `analysis/` — situational — codebase and business analysis docs
- `task/` — situational — execution tasks (only for large plans)
- `us/` — situational — user stories: requirement units (EARS + GWT) linked from specs
- `integration/` — situational — third-party integration notes
- `record/` — situational — reports and process records
- `reference/` — situational — external references
- `prompt/` — situational — reusable agent prompt templates
- `report/` — situational — generated reports and rendered deliverables

## Rules

- Keep one main version for one topic.
- `spec` says what the system should do.
- `plan` says how to do it.
- Use `task` only for large plans.
- Use `issue` for a development problem, the fix, and the verification result.
- Use `analysis` for exploratory codebase or business analysis that informs later docs.
- Write a decision record for major business, architecture, product-shape, or technology choices with real trade-offs.
- Keep long-term knowledge in `memory/`.
- Keep reports and evidence in `record/`.
