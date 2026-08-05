# Docs

This directory stores long-term project documents.

## Front Matter

Every doc should start with:

```md
---
id: <type>-<five-digit-number>-<slug>
type: analysis|decision|design|idea|integration|issue|memory|operation|plan|prd|prompt|record|report|spec|task|us
status: draft   # start here; promote per kind (see Front Matter Rules below)
---
```

Write the document description or comment after the front matter.

## Front Matter Rules

- `id` uses `<type>-<five-digit-number>-<slug>`, for example `spec-00001-doc-front-matter`.
- One document per topic, amended in place. There is no addendum document. When a doc must not be rewritten (published, or cited outside this repo), write a new one carrying `supersedes: [<old id>]` and set the old doc to `archived`.
- `status` has two sub-vocabularies, by document kind:
  - **Living docs** (`spec`, `design`, `decision`, `prd`, `idea`, `analysis`, `integration`, `reference`, `us`, `memory`, `operation`, `record`, `prompt`, `report`): `draft` (work in progress) -> `active` (the current live version / source of truth) -> `archived` (kept for history; no longer the current live version, e.g. superseded by or folded into another doc).
  - **Work items** (`issue`, `plan`, `task`): `draft` (pre-triage) -> `open` (tracked, not yet resolved) -> `resolved` (fix/work applied **and** verified). Terminal alternatives: `wontfix` (deliberately not acting, or the item became invalid / overtaken by events) and `archived` (the *document* was superseded, independent of whether the work was done).
- `archived` is a document-lifecycle state ("this file is no longer the live source"), not a synonym for "done". Record a work item's outcome with `resolved` or `wontfix`, never by archiving it.
- Product flow is `idea -> prd -> spec` when the later stage exists, and each stage carries the previous one as `parent`.
- `us` (user story) docs own a requirement unit (value statement + EARS requirements + GWT acceptance). Requirement ids carry the doc id, e.g. `us-00001-FR-1` and `us-00001-AC-1.1`.
- Relation rules:
  - A field the document's type does not carry must not appear at all.
  - **Declare each edge once**, on the document that depends on the other. Do not
    write the inverse edge on the far end; derive it by reading or by script.
  - `constrains` is the exception that proves the rule: it points downstream, so it
    only lists documents that are bound by the choice but do not point back at it.
    When a doc already declares `implements: [<the decision>]`, that edge exists —
    do not repeat it in the decision's `constrains`.
  - Every listed id is a **full** `<type>-<nnnnn>-<slug>` id of a document that
    exists. Never a bare `plan-00007`.

## Relations

| Field | Meaning |
| --- | --- |
| `parent` | which doc this one is *part of*, or the next stage of — single-valued, and only six types carry it |
| `implements` | this doc makes the listed docs real |
| `informs` | this doc is input for the listed docs without binding them |
| `motivated_by` | what created the need for this doc |
| `constrains` | the docs this doc's choice binds |
| `blocks` | what this doc blocks or clarifies |
| `verifies` | the requirements or docs this doc verifies |
| `supersedes` | the doc this one replaces, paired with `archived` on the old doc |

Everything except `parent` is multi-valued: write ids as an inline list, and omit
the field entirely when it is empty.

```md
---
id: plan-00010-operation-log-implementation
type: plan
status: open
implements: [spec-00001-operation-log-component, design-00008-operation-log-component]
---
```

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

- Keep one document per topic, and amend it in place.
- `spec` says what the system should do.
- `plan` says how to do it.
- Use `task` only for large plans.
- Use `issue` for a development problem, the fix, and the verification result.
- Use `analysis` for exploratory codebase or business analysis that informs later docs.
- Write a decision record for major business, architecture, product-shape, or technology choices with real trade-offs.
- Keep long-term knowledge in `memory/`.
- Keep reports and evidence in `record/`.
