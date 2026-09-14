# Specs

This directory stores feature specs.
Use `TEMPLATE.md` for front matter.

## Must Include

- context aligned to `CONTEXT.md`
- story slices, each naming the ids it delivers
- system requirements, EARS numbered `spec-<n>-FR-<i>`, acceptance
  `spec-<n>-AC-<i>.<k>`; error and rejection behaviour is an Unwanted requirement,
  not a table
- acceptance for every requirement: each `spec-<n>-FR-<i>` needs at least one
  `spec-<n>-AC-<i>.<k>`; an `FR` no acceptance references is unverified
- links to the `rule/` docs the feature obeys
- links to the `quality/` docs and the `quality-<n>-QS-<i>.<k>` scenarios the
  feature must hold (§7). Every spec cites at least the system's baseline
  quality doc; a scenario the feature alone introduces is written in `quality/`
  first and cited here, never inlined. When the spec is itself the entry point
  (no `prd`), the quality doc it cites is one too (`parent` empty)
- links to the `design/` docs it builds. Required before the spec turns `active`
  whenever any `FR` introduces or changes structure that outlives one `plan` — a
  module or boundary, a data model, an API or file-format contract, a state
  lifecycle, a cross-component interaction. When none does, say so in one line
  (`No design: <why>`) so the omission is a decision, not a gap
- open questions — what is still undecided

Add more when useful.

## Machine-readable form (item grammar)

Documents in this folder are parsed in the form below; a line that does not fit
is a parse error:

- A requirement declaration is a whole line, in one of two forms:
  - list item: `- **spec-<n>-FR-<i>** (<EARS type>) <text>`; indented lines that
    follow are continuation lines
  - decision-table row: `| **spec-<n>-FR-<i>** | <cell>… |`; no continuation lines
  - `(<EARS type>)` is the recommended annotation; its absence is not an error yet
- An acceptance criterion starts `- **spec-<n>-AC-<i>.<k>** (spec-<n>-FR-<i>)`;
  the attribution in parentheses is required (missing = unattributable parse
  error). Given / When / Then each take a continuation line.
- Only declarations prefixed with **this document's id** belong to this
  document; a whole-line reference to another document's item is not a
  declaration and is not diagnosed.
- In prose, item ids are always in backticks; **a bold id is the declaration
  form only** — a line that **starts with a bold item id** and does not fit the
  forms above is a parse error (a bold id mid-line is not affected, but the
  convention is still backticks in prose).

## Relations

- `parent` — a `prd`, an `idea`, or empty when the spec is itself the entry point.
- The `plan` declares `implements: [<this spec>]`; the `design`, the `rule`, and
  the `quality` doc declare `informs: [<this spec>]`.

## Exclude

- business rules of any size (use `rule/`)
- quality requirements and their measures (use `quality/`); the behaviour when
  a quality target is exceeded stays here as an Unwanted requirement
- implementation shape of any size or kind (use `design/`)
- long product background (use `prd/`)
- task breakdown (use `plan/`)
- process reports (use `record/`)

## Note

A spec is one feature — a coherent, shippable capability delivered as one
increment. It holds the requirements and their acceptance, and links to
everything else.

## Sizing and Splitting

The one-feature definition above is also a size rule. These are review
triggers, not hard gates — but when one fires, decide deliberately instead of
appending by default:

- a new revision round mostly **adds** requirements instead of amending
  existing ones — the "feature" has become a product area
- an auditor can no longer read the whole spec in one pass (as an order of
  magnitude: past ~20 FRs or ~500 body lines)
- the Stories table no longer describes one increment

How to split:

1. **New capability → new spec.** Grow sideways, not downward: open a new spec
   with the same `parent` (prd/idea) instead of appending FRs to an existing
   `active` spec. This is the default.
2. **Decomposing an oversized spec.** Write the replacement specs, each
   carrying `supersedes: [<old spec id>]`; set the old spec to `archived`
   Requirement ids are namespaced by doc
   id, so they are **not** renumbered or migrated: existing `record` rows and
   `plan` scopes keep pointing at the archived spec's items, which remain
   resolvable — history is evidence, not content to rewrite. New work cites
   the new specs' items.
3. Never split by moving FRs between two live specs — that breaks every
   acceptance row and delivery scope pointing at the moved ids.
