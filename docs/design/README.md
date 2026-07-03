# Designs

This directory stores reusable design docs.
Use `TEMPLATE.md` for front matter.

## Must Include

- reusable design content that is worth linking from one or more `spec` or
  `plan` docs

Add more when useful.

## Exclude

- task breakdown
- execution steps
- one-off implementation notes that belong inside a single `spec` or `plan`

## When To Extract

Keep the design inline in the `spec` (its Technical Design section) for small
scope. Extract a design doc here only when the design is (a) reused by more than
one `spec`/`plan`, or (b) large enough to review on its own. An extracted design
is independent: its `parent` may be a `spec`, a `plan`, or empty.

## Note

Prefer Mermaid for design diagrams, such as class, ER, sequence, and state diagrams.
