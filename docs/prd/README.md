# PRDs

This directory stores product requirement documents.
Use `TEMPLATE.md` for front matter.

## Must Include

- one-line summary
- vision and goals
- actors
- in-scope and out-of-scope boundaries
- functional requirements (what the product must do)
- quality goals — the quality attributes that matter for this product, ranked
  top first, in business language and without numbers; each names its
  `QUALITY.md` tag. The numbers are written in the `quality` docs this PRD
  parents
- user experience expectations
- risks and dependencies
- open questions — what is still undecided

Add more when useful.

## Relations

- `parent` — the `idea` this PRD grew out of, or empty when the PRD is the entry
  point.

## Exclude

- implementation design
- low-level technical solution

## Note

PRDs explain why and what, not how, for a human audience. A PRD does not own
formal requirement ids. Its functional requirements are carried by `spec/` docs
and its quality goals by `quality/` docs; both take the `prd` as `parent`, and
the `quality` docs come first because architecture decisions wait on them
(`QUALITY.md`).
