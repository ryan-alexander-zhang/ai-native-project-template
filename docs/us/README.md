# User Stories (`us`)

This directory stores user story documents (`type: us`). A user story is the
**owner of a requirement unit**: the value statement plus its EARS requirements
and GWT acceptance.
Use `TEMPLATE.md` for front matter.

## Must Include

- a value statement (`As a <role>, I want <capability>, so that <value>`)
- functional requirements written in EARS, numbered `us-<n>-FR-<i>`
- acceptance criteria written in GWT, numbered `us-<n>-AC-<i>.<k>`, each tracing
  to a requirement

## Requirements (EARS) and Acceptance (GWT)

- Every requirement uses an EARS pattern (Ubiquitous / Event `When` / State
  `While` / Optional `Where` / Unwanted `If…then` / Complex) and is tagged with
  it. See the reference table in `TEMPLATE.md`.
- Requirement ids carry the doc's `us-<n>` id (drop the slug), so they are
  globally unique: reference them from any doc as `us-00001-FR-2`.
- Every GWT scenario cites the `us-<n>-FR-<i>` it verifies, and covers unwanted
  paths, not just the happy path.

## Parent

`parent` is the `spec` this story belongs to, or the `prd` when the story is
part of a product backlog before a spec exists. When the story maps to a PRD
functional requirement, set `function_requirement_id` to that unique `FR-xx`.

## Exclude

- technical design (belongs in the parent `spec/` or a `design/` doc)
- task breakdown (belongs in `plan/` or `task/`)

## Note

Ids and filenames use the `us-00001-...` prefix. A spec links its stories; a
story links back to its spec. Most small features can keep a single story inline
in the spec — create a separate story doc when there are several, or when a
story is reused or tracked on its own.
