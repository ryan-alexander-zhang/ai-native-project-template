# Analyses

Analysis docs. Front matter: `TEMPLATE.md`.

## Must Include

- codebase analysis
- business analysis
- gap analysis
- comparative analysis
- discovery artefacts — event storming boards, domain storytelling; a sibling
  data file (a board DSL export) lives in `<slug>/source/` next to the doc, no
  front matter, excluded from board and drift checks; the doc points at it

Add more when useful.

## Relations

- `parent` — empty, or the catalog `analysis` this entry is part of.
- `informs` — the `spec`, `design`, `plan`, or `decision` this analysis feeds.

## Exclude

- final decisions
- formal requirements
- execution plans
