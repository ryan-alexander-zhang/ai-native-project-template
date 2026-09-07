# Docs

This directory stores long-term project documents.

## Front Matter

Every doc should start with:

```md
---
id: <type>-<five-digit-number>-<slug>
type: analysis|decision|design|idea|integration|issue|operation|plan|prd|prompt|record|reference|report|rule|spec|task
status: draft   # start here; promote per kind (see Front Matter Rules below)
---
```

Write the document description or comment after the front matter.

## Front Matter Rules

- `id` uses `<type>-<five-digit-number>-<slug>`, for example `spec-00001-doc-front-matter`.
- An `id` is **unique across the whole repo**: no two documents may declare the same one. (Files the whiteboard's `exclude` config hits are not documents here: they take no number and count as no collision.) Allocating the next free number per type is what keeps a new document from colliding; a collision that already exists is surfaced by the whiteboard as an anomaly on **every** file declaring that id, and every action addressed by it is refused until one of them is given a free id.
- One document per topic, amended in place. There is no addendum document. When a doc must not be rewritten (published, or cited outside this repo), write a new one carrying `supersedes: [<old id>]` and set the old doc to `archived`.
- `status` has two sub-vocabularies, by document kind:
  - **Living docs** (`spec`, `design`, `rule`, `decision`, `prd`, `idea`, `analysis`, `integration`, `reference`, `operation`, `record`, `prompt`, `report`): `draft` (work in progress) -> `active` (the current live version / source of truth) -> `archived` (kept for history; no longer the current live version, e.g. superseded by or folded into another doc).
  - **Work items** (`issue`, `plan`, `task`): `draft` (pre-triage) -> `open` (tracked, not yet resolved) -> `resolved` (fix/work applied **and** verified). Terminal alternatives: `wontfix` (deliberately not acting, or the item became invalid / overtaken by events) and `archived` (the *document* was superseded, independent of whether the work was done).
- `archived` is a document-lifecycle state ("this file is no longer the live source"), not a synonym for "done". Record a work item's outcome with `resolved` or `wontfix`, never by archiving it.
- A **substantive revision** of an `active` `spec`, `rule`, or `design` goes
  through the **revision round**: demote it to `draft` on
  the board, revise, audit, and re-accept — never edit the `active` file in
  place. Typo-level fixes are exempt; when in doubt, it is substantive.
- `decided_by` (`decision` only, not a relation): `human` when a person made the choice, `agent` when an agent made it unattended. Written only by autopilot runs (`AUTOPILOT.md`); absent means a human was in the loop.
- Product flow is `idea -> prd -> spec` when the later stage exists, and each stage carries the previous one as `parent`.
- There are exactly two requirement id namespaces, and both carry their doc id:
  - `spec` owns **system requirements** — `spec-00001-FR-1`, acceptance `spec-00001-AC-1.1`.
  - `rule` owns **business rules** — `rule-00001-BR-1`, acceptance `rule-00001-AC-1.1`.
  The test for which one applies: remove the software. If the statement is still
  true, it is a rule. A requirement that applies a rule cites it instead of
  restating it.
- A **story** is a planning token, not a document: a row in the spec's Stories
  table naming one shippable slice and the requirement and rule ids it delivers.
  Stories own no id namespace and carry no acceptance of their own.
- Relation rules:
  - A field the document's type does not carry must not appear at all.
  - **Declare each edge once**, on the document that depends on the other. Do not
    write the inverse edge on the far end; derive it by reading or by script.
  - Three fields are the exception, because they point **downstream** and are
    therefore declared on the upstream doc: `informs`, `constrains`, and `blocks`.
    A `design` carries `informs: [<the spec it feeds>]`; an `issue` carries
    `blocks: [<the plan it holds up>]`; a `decision` carries `constrains: [...]`.
    Each still declares its edge once — just from the other end.
  - `constrains` additionally lists only documents that do not point back at it:
    when a doc already declares `implements: [<the decision>]`, that edge exists —
    do not repeat it in the decision's `constrains`.
  - Every listed id is a **full** `<type>-<nnnnn>-<slug>` id of a document that
    exists. Never a bare `plan-00007`. Two fields may additionally name
    **requirement-item ids** (`spec-<nnnnn>-FR-<i>`, `rule-<nnnnn>-BR-<i>`, or
    an `AC-<i>.<j>`) of items that exist: a `record`'s `verifies` (what it
    checked), and a `plan`'s `implements` — item ids there declare the plan's
    **delivery scope**: the items whose acceptance must be verified by that
    plan's records before the plan may turn `resolved`. An AC id in `implements` puts its owning item in
    scope; a whole spec/rule doc id puts every item of that doc in scope.

## Relations

| Field | Meaning |
| --- | --- |
| `parent` | which doc this one is *part of*, or the next stage of — single-valued, and only five types carry it |
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
- `spec/` — **core** — feature specs: story slices, system requirements, links to rules and design
- `rule/` — **core** — business rules: decision tables and the examples verifying them
- `plan/` — **core** — implementation plans
- `decision/` — **core** — durable decision records
- `issue/` — **core** — development issues, fixes, and verification
- `operation/` — **core** — runbook and operations docs
- `idea/` — **core** — early ideas (some projects skip and start at `prd/`)
- `design/` — situational — durable structural design docs
- `analysis/` — situational — codebase and business analysis docs
- `task/` — situational — execution tasks (only for large plans)
- `integration/` — situational — third-party integration notes
- `record/` — situational — reports and process records
- `reference/` — situational — external references
- `prompt/` — situational — reusable agent prompt templates
- `report/` — situational — generated reports and rendered deliverables

## Rules

- Keep one document per topic, and amend it in place.
- `rule` says what is true in the business, with or without the software.
- `spec` says what the system should do.
- `plan` says how to do it.
- Use `task` only for large plans.
- Use `issue` for a development problem, the fix, and the verification result.
- Use `analysis` for exploratory codebase or business analysis that informs later docs.
- Write a decision record for major business, architecture, product-shape, or technology choices with real trade-offs.
- Keep reports and evidence in `record/`.
