# Docs

Long-term project documents.

## Front Matter

Every doc starts with:

```md
---
id: <type>-<five-digit-number>-<slug>
type: analysis|decision|design|idea|integration|issue|operation|plan|prd|prompt|quality|record|reference|report|rule|spec
status: draft   # start here; promote per kind (Front Matter Rules)
---
```

Description or comment follows the front matter.

## Front Matter Rules

- `id` = `<type>-<five-digit-number>-<slug>`, e.g. `spec-00001-doc-front-matter`.
- An `id` is **unique across the repo**. Files hit by `exclude` in `whiteboard.config.yaml` are not documents: no number, no collision. Allocate the next free number per type; an existing collision is an error on **every** file declaring that id until one is renumbered.
- One document per topic, amended in place; no addendum documents. A doc that must not be rewritten (published, cited outside the repo) gets a new one carrying `supersedes: [<old id>]`; the old doc turns `archived` with `superseded_by: [<new id>]`. A `decision` is stricter: a change to the choice itself always supersedes (`docs/decision/README.md` Revision).
- `status` by document kind:
  - **Living docs** (`spec`, `design`, `rule`, `quality`, `decision`, `prd`, `idea`, `analysis`, `integration`, `reference`, `operation`, `record`, `prompt`, `report`): `draft` -> `active` (source of truth) -> `archived` (history; superseded or folded into another doc).
  - **Work items** (`issue`, `plan`): `draft` (pre-triage) -> `open` (tracked) -> `resolved` (applied **and** verified). Terminal alternatives: `wontfix` (deliberately not acting, or invalid / overtaken) and `archived` (the *document* was superseded, regardless of the work).
- `archived` = "no longer the live source", never "done". A work item's outcome is `resolved` or `wontfix`.
- A **substantive revision** of an `active` `spec`, `rule`, `quality`, or `design` takes the **revision round**: demote to `draft`, revise, audit, re-accept; never edit the `active` file in place. Typo-level fixes exempt; when in doubt, substantive.
- `decided_by` (`decision` only, not a relation): `human` or `agent`. Written only by autopilot runs (`AUTOPILOT.md`); absent = a human was in the loop.
- Product flow: `idea -> prd -> quality -> spec` when the later stage exists; `quality` and `spec` both carry the `prd` as `parent`; architecture decisions and `design` docs wait on `quality` (`QUALITY.md`).
- Exactly three requirement id namespaces, each carrying its doc id:
  - `spec` — **system requirements** — `spec-00001-FR-1`, acceptance `spec-00001-AC-1.1`.
  - `rule` — **business rules** — `rule-00001-BR-1`, acceptance `rule-00001-AC-1.1`.
  - `quality` — **quality requirements** — `quality-00001-QR-1`, scenario `quality-00001-QS-1.1`.
  Tests: remove the software — still true → rule. Remove the measure — nothing left to check → not a quality requirement; the rest is a system requirement. A requirement that applies a rule or holds a scenario cites it, never restates it.
- A **story** is a planning token, not a document: a row in the spec's Stories table naming one shippable slice and the ids it delivers. No id namespace, no acceptance of its own.
- Relations:
  - A field the type does not carry must not appear.
  - **Declare each edge once**, on the doc that depends on the other; never write the inverse on the far end.
  - Exception, declared upstream because they point **downstream**: `informs`, `constrains`, `blocks`. A `design` carries `informs: [<spec>]`; an `issue` carries `blocks: [<plan>]`; a `decision` carries `constrains: [...]`.
  - `constrains` lists only docs that do not point back: a doc declaring `implements: [<the decision>]` is not repeated there.
  - Every listed id is a **full** `<type>-<nnnnn>-<slug>` id of an existing doc; never a bare `plan-00007`. Requirement-item ids (`spec-<nnnnn>-FR-<i>`, `rule-<nnnnn>-BR-<i>`, `quality-<nnnnn>-QR-<i>`, `AC-<i>.<j>`, `QS-<i>.<j>`) of existing items are additionally allowed in: a `record`'s `verifies`; a `plan`'s `implements` — its **delivery scope**, the items whose acceptance its records must verify before `resolved` (an AC / QS id puts its item in scope; a doc id puts every item in scope); a `design`'s `implements` (`QS` ids it realises); a `decision`'s `motivated_by` (`QS` ids it serves).

Every rule above is checked by `scripts/check-docs-drift.sh` (pre-commit and CI); the whiteboard is optional and adds a board view, clarify and co-write sessions.

## Relations

| Field | Meaning |
| --- | --- |
| `parent` | the doc this one is *part of* or the next stage of; single-valued; only the types whose README lists it |
| `implements` | makes the listed docs real |
| `informs` | input for the listed docs without binding them |
| `motivated_by` | what created the need for this doc |
| `constrains` | the docs this doc's choice binds |
| `blocks` | what this doc blocks or clarifies |
| `verifies` | the requirements or docs this doc verifies |
| `supersedes` | the doc this one replaces; paired with `archived` on the old doc |
| `superseded_by` | the doc that replaced this one; the only edit an `archived` doc takes |

All but `parent` are multi-valued inline lists; omit an empty field.

```md
---
id: plan-00010-operation-log-implementation
type: plan
status: open
implements: [spec-00001-operation-log-component, design-00008-operation-log-component]
---
```

## Folders

**core** = most projects need it; **situational** = only when the project calls for it.

- `prd/` — **core** — product requirements
- `spec/` — **core** — feature specs: story slices, system requirements, links to rules, quality, and design
- `rule/` — **core** — business rules: decision tables and the examples verifying them
- `quality/` — **core** — quality requirements: tagged `QR`s and their six-part scenarios, per `QUALITY.md`
- `plan/` — **core** — implementation plans
- `decision/` — **core** — durable decision records
- `issue/` — **core** — development issues, fixes, and verification
- `operation/` — **core** — runbook and operations docs
- `idea/` — **core** — early ideas (some projects start at `prd/`)
- `design/` — situational — durable structural design docs
- `analysis/` — situational — codebase and business analysis
- `integration/` — situational — third-party integration notes
- `record/` — situational — reports and process records
- `reference/` — situational — external references
- `prompt/` — situational — reusable agent prompt templates
- `report/` — situational — generated reports and rendered deliverables

## Rules

- One document per topic, amended in place.
- `rule`: what is true in the business, with or without the software.
- `spec`: what the system does.
- `quality`: how well, with a measure, verified at build, release, and runtime.
- `plan`: how to do it.
- `issue`: a development problem, its fix, its verification.
- `analysis`: exploratory codebase or business analysis that informs later docs.
- `decision`: major business, architecture, product-shape, or technology choices with real trade-offs.
- `record/`: reports and evidence.
