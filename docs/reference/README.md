# References

External reference materials. Front matter: `TEMPLATE.md`.

## Naming

- `reference-<five-digit-number>-<slug>.md`, per [docs/README.md](../README.md),
  e.g. `reference-00001-api-provider-docs-summary.md`.
- A living doc like any other: same `id` / `type` / `status` front matter, on
  the board. Capture date in the body, not the filename.
- Raw material (copied vendor docs, schemas, excerpts) lives in `<slug>/source/`
  next to the document: no front matter, excluded from board and drift checks.
  Only the distilled `reference-<nnnnn>-<slug>.md` is a document; its §1 Source
  points at the raw path.

## Must Include

- external links
- paper or article summaries
- official doc excerpts
- benchmark research

Add more when useful.

## Relations

- `informs` — the `spec` / `design` / `plan` docs this material is input for.

## Exclude

- final project decisions
- final rules and constraints

## Note

Input only; never a replacement for formal documents.
