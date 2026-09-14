# Reports

Generated reports and rendered deliverables. Front matter on the Markdown
source: `TEMPLATE.md`.

## Must Include

- a Markdown source document with front matter
- rendered exports alongside it when needed (`.html`, `.pdf`), sharing the slug

## Relations

- `informs` — the docs this report feeds. A remediation plan declares
  `implements: [<this report>]` on its own side.

## Exclude

- process records and evidence (use `record/`)
- external input material (use `reference/`)

## Note

A report is a polished, human-facing deliverable; `record/` holds internal
process evidence. The Markdown source is the truth; rendered files are exports.
