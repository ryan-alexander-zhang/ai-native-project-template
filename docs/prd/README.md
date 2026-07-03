# PRDs

This directory stores product requirement documents.
Use `TEMPLATE.md` for front matter.

## Must Include

- one-line summary
- vision and goals
- actors
- in-scope and out-of-scope boundaries
- functional requirements with unique `FR-xx` ids
- user experience expectations
- risks and dependencies

Add more when useful.

## Exclude

- implementation design
- low-level technical solution

## Note

PRDs explain why and what, not how. Each PRD functional requirement (`FR-xx`)
is realized by one or more `spec/` docs, and detailed as `us/` (user story) docs.
A user story that maps to a PRD requirement links back via
`function_requirement_id`.
