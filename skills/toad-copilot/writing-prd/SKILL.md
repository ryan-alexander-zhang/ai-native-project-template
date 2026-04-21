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
8. Run the validation loop below until it passes.
9. Report the path back to the user.

## Validation Loop

1. Save the edited PRD.
2. Run:
   `python3 scripts/validate_doc.py "<file path>"`
   Resolve `scripts/validate_doc.py` relative to this skill directory.
3. If validation fails:
   - read the error message
   - fix the PRD in place
   - run the validator again
4. Do a final self-check against the user request:
   - confirm every placeholder line is gone
   - confirm the required sections are still present in the original order
   - confirm there are at least two unique `FR-xx` checklist items
   - confirm the PRD actually reflects the requested scope, not just the scaffold
5. Only report success after the validator passes and the self-check passes.

## Gotchas

- Do not invent the file name or front matter by hand. Always start with `scripts/create_prd.py`.
- `parent` must match `role`: `main` uses an `idea-xxxxx-...` id, while `patch` uses a `prd-xxxxx-...` id.
- Keep the scaffold's exact top-level headings. Renaming or reordering them can break downstream validation.
- Replace every angle-bracket placeholder line like `<...>`. A PRD with scaffold placeholders still present is invalid.
- Keep `Functional Requirements` as checklist items using unique `FR-xx` ids. Do not switch to prose paragraphs.
- Use `--output-dir` only when the user explicitly asks for a different destination.

## Generated File

By default the script saves to `docs/prds` in the current project. Use `--output-dir` only when
the user explicitly wants a different location.

- front matter with `id`, `type`, `role`, `status`, and `parent`
- the PRD document
- these sections:
  `One-line Summary`, `Vision & Goals`, `Actor`, `In Scope`, `Out of Scope`,
  `Functional Requirements`, `User Experience`, `Risks`, and `Dependencies`

Fill the generated file in place.
