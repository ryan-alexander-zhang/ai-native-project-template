---
name: writing-prd
description: >
  Save a PRD in `docs/prds` as the current project's standard PRD.
  Use this whenever the user wants a PRD, product requirements doc, wants to turn an idea into
  scoped requirements, wants to define `FR-xx` functional requirements, or wants to add a patch to
  an existing PRD, even if they do not explicitly say "PRD".
compatibility: Requires python3, a POSIX environment, and write access to the target project's `docs/prds`.
---

# Writing PRD

Use this skill when the user wants a PRD saved in the current project's `docs/prds`.

## Defaults

- Use `status: draft` unless the user explicitly asks for `active` or `archived`.
- Derive a short slug from the feature name or request.
- Keep the PRD short and concrete.
- Use unique `FR-xx` ids in `Functional Requirements`.

## Workflow

1. Infer whether the request is for a new main PRD or a patch to an existing PRD.
2. If it is unclear, ask the user to choose:
   - `A.` Add a patch to the inferred PRD
   - `B.` Create a new main PRD
   - `C.` None, tell me what to do
3. Pick the parent id that matches the role:
   - `main` -> idea brief id
   - `patch` -> PRD id
4. Run:
   `python3 scripts/create_prd.py "<slug or title>" --json [--role main|patch] [--status draft|active|archived] --parent <id>`
   Resolve `scripts/create_prd.py` relative to this skill directory.
5. Open the new file path returned by the script.
6. Replace every placeholder line with concise content based on the user's request.
7. Keep the generated front matter and section structure. Do not rewrite the scaffold by hand.
8. Save the file.
9. Run:
   `python3 scripts/validate_doc.py "<file path>"`
   Resolve `scripts/validate_doc.py` relative to this skill directory.
10. If validation fails, fix the document and rerun the validator before reporting success.
11. Report the path back to the user.

## Generated File

By default the script saves to `docs/prds` in the current project. Use `--output-dir` only when
the user explicitly wants a different location.

- front matter with `id`, `type`, `role`, `status`, and `parent`
- the PRD document
- these sections:
  `One-line Summary`, `Vision & Goals`, `Actor`, `In Scope`, `Out of Scope`,
  `Functional Requirements`, `User Experience`, `Risks`, and `Dependencies`

Fill the generated file in place.
