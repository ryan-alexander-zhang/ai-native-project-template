# Document Management

## Purpose

Document-work companion to [DEVELOPMENT.md](DEVELOPMENT.md) and
[TESTING.md](TESTING.md): placement, status transitions, done. Taxonomy,
folders, front matter: [docs/README.md](docs/README.md).

## Placement

- Anything under `docs/` goes where [docs/README.md](docs/README.md) says; smallest correct folder.
- Repo-wide policy and workflow stay in the root docs, not under `docs/`.
- One current doc per topic; no parallel structures.
- A taxonomy or folder rule change updates `docs/README.md`, the templates, and the folder `README.md` files together.

## Status Workflow

Values defined in [docs/README.md](docs/README.md). Never change status silently.

- New docs start `draft`; a doc with open questions stays `draft`.
- After creating or substantively updating a `draft`, ask if it is reviewed; if yes, promote: living docs `active`, work items `open` (autopilot replaces both rounds per `AUTOPILOT.md`).
- Work done: work item `resolved`; living docs stay `active`. A `plan` with `spec`/`rule`/`quality` items in scope needs its `docs/record/` checklist first.
- Deliberately not acting, or invalid: `wontfix`. Never record an outcome by archiving.
- `archived` only for a doc replaced by another carrying `supersedes`.
- Never commit a `draft` without promoting it or confirming the exception.

## Definition of Done

A documentation change is done when:

- the doc is in the correct location with valid front matter
- `scripts/check-docs-drift.sh` passes
- links, paths, and examples were checked
- no topic has two live docs
- no doc it governs is left in `draft`
