---
id: decision-00004-relations-are-explicit-fields
type: decision
status: active
parent:
---

# `parent` means containment; every other relation is its own field

## Context

Front matter had one link field, `parent`, and the taxonomy assigned it five
different meanings depending on the document type:

| Declared use | Relation it actually expressed |
| --- | --- |
| `spec` → `prd` | stage advance in the product flow |
| `us` → `spec` | containment (the story is part of the feature) |
| `decision` → `idea`/`prd`/`spec` | cause (what created the need to choose) |
| `issue` → `task`/`plan`/`spec` | reference (what it blocks or clarifies) |
| `plan` → `design` (in practice) | basis (what the plan builds) |

Collapsing five relations into one field makes the document graph unqueryable.
"Which requirements have no plan", "which designs are unimplemented", "what does
this decision affect", "what is blocking this plan" all become the same edge, so
none of them can be answered — by a reader or by a script.

It also forces a tree onto a graph. A `design` is explicitly reusable by several
specs and plans, but a single-valued `parent` can only hold one of them, so the
other edges were recorded in prose or nowhere.

Two smaller symptoms came from the same root: a `parent` with no stated format
picked up bare ids that resolve to nothing (`parent: plan-00007`), and there was
no way for an `archived` doc to point at what replaced it.

## Decision

Narrow `parent` to a single meaning and give every other relation its own
optional, multi-valued field.

`parent` answers only: *which document is this one a part of, or the next stage
of?* Only these carry it: `task` → `plan` (required), `record` → `plan`,
`spec` → `prd`/`idea`, `prd` → `idea`, and `analysis` → the catalog analysis
that enumerates it.

Every other type is not part of another document, so `parent` is not merely empty
for them: **it must not appear**. It is absent from their templates, which is a
stronger guard than a rule saying "leave it blank", and it gives a checker
something exact to reject — and it means the templates, not this prose, are the
live list.

The relation fields are `implements`, `informs`, `motivated_by`, `constrains`,
`blocks`, `verifies`, and `supersedes`, defined in
[docs/README.md](../README.md#relations).

Supporting rules:

- Each edge is declared once, on the document that depends on the other. No
  inverse edges to keep in sync.
- Every listed id is a full `<type>-<nnnnn>-<slug>` id of a document that exists.
- A `plan` must carry `implements`; an `issue` must carry `blocks`. Without them
  neither traces to anything.
- `plan` leaves `parent` empty: it is not part of a spec and not a fourth stage
  on the product flow. It attaches with `implements`, which is also why a plan
  built on a `design` or a review `report` — the common case in a library repo —
  is now expressible without abusing `parent`.

## Options considered

- **Keep one overloaded `parent`.** Rejected: it is the status quo, and the cost
  is that no relation question can be answered mechanically.
- **Add relation fields but keep `parent` general too.** Rejected: two ways to
  express the same edge guarantees they diverge, and a checker cannot decide
  which is authoritative.
- **Declare both directions of every edge.** Rejected: doubles the upkeep on
  every document and goes stale silently. `constrains` is the one downstream
  pointer kept, because a decision's reach is the thing readers look for and it
  has no natural home on the bound document.
- **A separate link file or index instead of front matter.** Rejected: the link
  would then live away from the document it describes, which is how the bare-id
  and stale-link problems started.

## Consequences

- `docs/README.md` — the front matter block, a new "Relations" section with the
  `parent` table, the relation-field table, and the relation rules; the five
  per-type `parent` bullets are gone.
- All 16 `docs/*/TEMPLATE.md` — `parent` is annotated with what it means for that
  type, and the type's usual relation fields are present as commented placeholders.
- Per-folder `README.md` — each gained a short "Relations" section stating whether
  `parent` is required or empty and which fields that type uses.
- `DOCUMENT.md` — the documentation Definition of Done now includes relation
  validity.
- `docs/operation/operation-00001-enforce-frozen-docs-check.md` — `parent:
  decision-00003` became `implements: [decision-00003-docs-system-owned-by-main]`.
- Benefit: the graph becomes checkable. A script can list plans that implement
  nothing, requirements with no verifying record, and relation ids that do not
  resolve. That checker does not exist yet and is the natural follow-up.
- Trade-off accepted: front matter is longer, and authors must pick a field
  instead of defaulting to `parent`. The per-type templates carry the right
  fields so the choice is usually already made for them.
- Migration debt on `lang/*` branches, outstanding until each branch merges this
  skeleton change and runs a pass:
  - instance docs still use the old overloaded `parent` (`plan` → `design`,
    `issue` → `plan`, `decision` → upstream); each becomes the right relation
    field.
  - docs of the ten types that no longer carry `parent` still have the line,
    usually empty; it is deleted outright.
