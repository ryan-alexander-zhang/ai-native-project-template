# Specs

This directory stores feature specs.
Use `TEMPLATE.md` for front matter.

## Must Include

- context aligned to `CONTEXT.md`
- story slices, each naming the ids it delivers
- system requirements, EARS numbered `spec-<n>-FR-<i>`, acceptance
  `spec-<n>-AC-<i>.<k>`; error and rejection behaviour is an Unwanted requirement,
  not a table
- links to the `rule/` docs the feature obeys
- links to the `design/` docs it builds
- open questions — what is still undecided

Add more when useful.

## Relations

- `parent` — a `prd`, an `idea`, or empty when the spec is itself the entry point.
- The `plan` declares `implements: [<this spec>]`; the `design` and the `rule`
  declare `informs: [<this spec>]`.

## Exclude

- business rules of any size (use `rule/`)
- implementation shape of any size or kind (use `design/`)
- long product background (use `prd/`)
- task breakdown (use `plan/` or `task/`)
- process reports (use `record/`)

## Note

A spec is one feature — a coherent, shippable capability delivered as one
increment. It holds the requirements and their acceptance, and links to
everything else.
