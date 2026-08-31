---
id: report-00001-example-slug
type: report
status: draft|active|archived
informs: [<id>, ...]                          # the docs this report is input for
---

# Report: <the subject>

> One sentence: what this report tells its reader.

## 1. Summary

<The conclusion first, in a few sentences a reader can act on without reading
the rest.>

## 2. Scope and Method

- Covers: <what was examined, and the period or version>
- Method: <how the material was gathered>

## 3. Findings

- <finding, with the evidence behind it>

## 4. Recommendations

- <what should happen next, and who owns it. A plan working these through
  declares `implements: [<this report>]` on its own side.>

## Exports (optional)

- <`<slug>.pdf` / `<slug>.html` — rendered from this Markdown source, which
  stays the source of truth>
