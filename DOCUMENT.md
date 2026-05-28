# Document Management

## Purpose

This file defines the minimum document management standard for this repo.

Use it to decide:
- where a document belongs
- how a document should be created or updated
- when a documentation change is complete

## Document Pattern

Keep document management simple:
- use the existing taxonomy before creating new structure
- keep one current main doc for one topic
- use patch docs only to extend a main doc
- prefer updating the canonical doc over creating duplicates
- keep links, templates, and taxonomy rules aligned

## Document Flow

### Choose Location

Use this stage to place the document in the correct root file or `docs/` folder.

Choose location should:
- use [docs/README.md](docs/README.md) as the source of truth for `docs/` taxonomy
- choose the smallest correct folder or canonical root doc
- avoid creating parallel structures for the same purpose

### Create or Update

Use this stage to write or revise the canonical document.

Create or update should:
- follow the required front matter for docs under `docs/`
- keep `type`, `role`, `status`, and `parent` consistent
- update the existing main doc when that is the canonical place

### Review

Use this stage to check that the document still fits the repo taxonomy.

Review stage should:
- verify links, examples, and referenced paths
- update templates or README files when taxonomy rules change
- keep naming and folder usage consistent

### Archive

Use this stage when a document is replaced or no longer current.

Archive stage should:
- keep one current main doc for one topic
- mark old versions clearly
- preserve history without leaving competing live docs

## Document Guides

- [docs/README.md](docs/README.md): source of truth for `docs/` front matter, folders, and read order.
- Folder `README.md` files under `docs/`: usage notes for each document area.

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
