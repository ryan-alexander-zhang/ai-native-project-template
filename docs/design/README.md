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

Default to a design doc here: a `spec` links its Technical Design to a `design/`
doc rather than embedding it. This keeps designs collectable in one folder, lets
a design be written before its spec and associated later, and lets one design be
reused by more than one `spec`/`plan`. An extracted design is independent: its
`parent` may be a `spec`, a `plan`, or empty.

Small-spec exception: keep the design inline in the `spec` (its Technical Design
section) when the scope is small, and extract it here once it is reused or large
enough to review on its own.

## Note

Prefer Mermaid for design diagrams, such as class, ER, sequence, and state diagrams.
