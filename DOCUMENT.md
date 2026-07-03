# Document Management

## Purpose

The document-work companion to [DEVELOPMENT.md](DEVELOPMENT.md) and
[TESTING.md](TESTING.md). [docs/README.md](docs/README.md) is the source of
truth for the `docs/` taxonomy, folders, and front matter; this file does not
restate them — it covers placement, status transitions, and done.

## Placement

- Place anything under `docs/` via [docs/README.md](docs/README.md); choose the smallest correct folder.
- Keep repo-wide policy and workflow in the root canonical docs, not under `docs/`.
- Keep one current main doc per topic; do not create parallel structures.
- When a taxonomy or folder rule changes, update `docs/README.md` and the affected templates and folder `README.md` files together.

## Status Workflow

Status values are defined in [docs/README.md](docs/README.md). Never change status silently.

- New docs start as `draft`.
- After creating or substantively updating a `draft` doc, ask if it is reviewed. If yes, promote it: living docs to `active`, work items to `open`.
- When the work is done, set the work item to `resolved`; living docs stay `active`.
- Do not commit a doc left in `draft`; promote it or confirm the exception first.

## Definition of Done

A documentation change is done when:

- the doc is in the correct location with valid front matter
- links, paths, and examples were checked
- no topic has two live main docs
- no doc it governs is left in `draft`
