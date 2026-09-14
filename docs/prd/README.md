# PRDs

Product requirement documents. Front matter: `TEMPLATE.md`.

## Must Include

- one-line summary
- vision and goals
- actors
- scale and context — the facts the `quality` profile is derived from: actor
  count and frequency, data volume and kinds, sensitivity, where actors and
  data live, operating hours, growth, budget. Orders of magnitude, not targets
- in-scope and out-of-scope boundaries
- functional requirements (what the product must do)
- quality goals — the attributes that matter, ranked top first, business
  language, no numbers; each names its `QUALITY.md` tag. Numbers live in the
  `quality` docs this PRD parents
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

Why and what, not how, for a human audience. A PRD owns no requirement ids:
functional requirements go to `spec/`, quality goals to `quality/`, both with
the `prd` as `parent`; `quality` comes first because architecture decisions
wait on it (`QUALITY.md`).
