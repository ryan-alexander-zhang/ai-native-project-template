# Specs

Feature specs. Front matter: `TEMPLATE.md`.

## Must Include

- context aligned to `CONTEXT.md`
- story slices, each naming the ids it delivers
- system requirements, EARS numbered `spec-<n>-FR-<i>`, acceptance
  `spec-<n>-AC-<i>.<k>`; error and rejection behaviour is an Unwanted requirement,
  not a table
- acceptance for every requirement: each `spec-<n>-FR-<i>` needs at least one
  `spec-<n>-AC-<i>.<k>`; an `FR` no acceptance references is unverified
- links to the `rule/` docs the feature obeys
- links to the `quality/` docs and `quality-<n>-QS-<i>.<k>` scenarios the
  feature must hold (§7): at least the system's baseline quality doc; a scenario
  the feature alone introduces is written in `quality/` first, never inlined.
  A spec that is the entry point (no `prd`) cites a quality doc that is one too
- links to the `design/` docs it builds, one per structural element
  (`docs/design/README.md` Kinds). Required before `active`
  whenever any `FR` introduces or changes structure outliving one `plan` — a
  module or boundary, a data model, an API or file-format contract, a state
  lifecycle, a cross-component interaction. Otherwise one line: `No design: <why>`
- open questions — what is still undecided

Add more when useful.

## Machine-readable form (item grammar)

A line that does not fit is a parse error:

- A requirement declaration is a whole line, in one of two forms:
  - list item: `- **spec-<n>-FR-<i>** (<EARS type>) <text>`; indented lines that
    follow are continuation lines
  - decision-table row: `| **spec-<n>-FR-<i>** | <cell>… |`; no continuation lines
  - `(<EARS type>)` is the recommended annotation; its absence is not an error yet
- An acceptance criterion starts `- **spec-<n>-AC-<i>.<k>** (spec-<n>-FR-<i>)`;
  the attribution in parentheses is required (missing = unattributable parse
  error). Given / When / Then each take a continuation line.
- Only declarations prefixed with **this document's id** belong to it; a
  whole-line reference to another doc's item is not a declaration.
- In prose, item ids are in backticks; **a bold id is the declaration form
  only** (a bold id mid-line is not diagnosed, but the convention holds).

## Relations

- `parent` — a `prd`, an `idea`, or empty when the spec is itself the entry point.
- The `plan` declares `implements: [<this spec>]`; the `design`, the `rule`, and
  the `quality` doc declare `informs: [<this spec>]`.

## Exclude

- business rules of any size (`rule/`)
- quality requirements and measures (`quality/`); the behaviour when a target is
  exceeded stays here as an Unwanted requirement
- implementation shape of any kind (`design/`)
- product background (`prd/`), task breakdown (`plan/`), process reports (`record/`)

## Note

A spec is one feature: a coherent, shippable capability delivered as one
increment. Requirements and acceptance here; links to everything else.

## Sizing and Splitting

Review triggers, not gates: a revision round mostly **adds** FRs (the feature
became a product area); an auditor cannot read it in one pass (~20 FRs or ~500
body lines); the Stories table no longer describes one increment.

1. **New capability → new spec** with the same `parent`, never FRs appended to
   an `active` spec. Default.
2. **Oversized spec**: replacement specs carrying `supersedes: [<old spec id>]`;
   old spec `archived`. Ids are namespaced by doc id, so nothing is renumbered:
   existing `record` rows and `plan` scopes keep resolving against the archived
   spec; new work cites the new specs.
3. Never move FRs between two live specs: it breaks every acceptance row and
   delivery scope pointing at them.
