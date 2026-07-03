# Document Management

## Purpose

This file defines how document work is done in this repo — the workflow
companion to [DEVELOPMENT.md](DEVELOPMENT.md) and [TESTING.md](TESTING.md).

Use it to decide:
- where a document belongs (root file vs `docs/` folder)
- what a documentation change must include
- when a documentation change is complete

[docs/README.md](docs/README.md) is the source of truth for the `docs/`
taxonomy, folders, front matter, and read order. This file does not restate
those rules; it covers placement, the change matrix, and the Definition of Done.

## Placement

- Use [docs/README.md](docs/README.md) to place anything under `docs/`; choose
  the smallest correct folder.
- Keep repo-wide policy and workflow in the canonical root docs (this file,
  `DEVELOPMENT.md`, `TESTING.md`, `ARCHITECTURE.md`, …), not under `docs/`.
- Do not create parallel structures for the same purpose; keep one current main
  doc per topic.
- Put external reference material under `docs/reference/`; do not mix it with
  canonical project docs.

## Document Matrix

| Doc change | Minimum requirement |
| --- | --- |
| New root policy doc | Keep it generic, scoped, and linked to the right canonical docs. |
| New main doc under `docs/` | Choose the correct folder and add valid front matter. |
| New patch doc under `docs/` | Set `role: patch` and point `parent` to the main doc it extends. |
| Taxonomy or folder rule change | Update `docs/README.md` and any affected templates or folder `README.md` files together. |
| Replace or supersede a doc | Keep one current main doc and mark old docs clearly. |
| Reference material import | Put it under `docs/reference/` and do not mix it with canonical project docs. |

## Definition of Done

A documentation change is done only when all of these are true:

- the document is in the correct location
- the required metadata is present when needed
- links, paths, and examples were checked
- related templates or taxonomy docs were updated when the rules changed
- no duplicate current source of truth was introduced
