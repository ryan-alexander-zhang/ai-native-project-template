# PRDs

This directory stores product requirement documents.
Use `TEMPLATE.md` for front matter.

## Must Include

- one-line summary
- vision and goals
- actors
- in-scope and out-of-scope boundaries
- functional requirements (what the product must do)
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
formal requirement ids. Implementation is carried by `spec/` docs, which may
take a `prd` as `parent`.
