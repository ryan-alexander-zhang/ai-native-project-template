# Analyses

This directory stores analysis docs.
Use `TEMPLATE.md` for front matter.

## Must Include

- codebase analysis
- business analysis
- gap analysis
- comparative analysis

Add more when useful.

## Relations

- `parent` — empty, or the catalog `analysis` that enumerates this entry when it
  really is part of one.
- `informs` — the docs this analysis feeds: a `spec`, `design`, `plan`, or the
  `decision` it led to.

## Exclude

- final decisions
- formal requirements
- execution plans
