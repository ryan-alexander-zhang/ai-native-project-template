# Reports

This directory stores generated reports and rendered deliverables.
Use `TEMPLATE.md` for front matter on the Markdown source.

## Must Include

- a Markdown source document with front matter
- rendered exports alongside it when needed (`.html`, `.pdf`), sharing the slug

## Relations

- `informs` — the docs this report feeds. A plan opened off the back of a review
  report declares `implements: [<this report>]` on its own side.

## Exclude

- process records and evidence (use `record/`)
- external input material (use `reference/`)

## Note

A report is a polished, human-facing deliverable produced from project work,
as opposed to `record/`, which holds internal process evidence. Keep the
Markdown source as the source of truth; rendered files are exports of it.
