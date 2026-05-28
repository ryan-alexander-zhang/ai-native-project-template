---
name: writing-prd
description: >
  Save a PRD in `docs/prd` as the current project's standard PRD.
  Use this whenever the user wants a PRD, product requirements doc, wants to turn an idea into
  scoped requirements, wants to define `FR-xx` functional requirements, or wants to add a patch to
  an existing PRD, even if they do not explicitly say "PRD".
compatibility: >
  Requires python3, a POSIX environment, and write access to the target project's `docs/prd`.
---

# Writing PRD

Use this skill when the user wants a PRD saved in the current project's `docs/prd`.

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
6. Re-open the source material that defines the request:
   - the user's request
   - any extra constraints from the conversation
   - for `main`, the parent idea brief when available
   - for `patch`, the parent PRD and the patch request itself
7. Replace every placeholder line with concise content based on the user's request.
8. Keep the generated front matter and section structure. Do not rewrite the scaffold by hand.
9. Run the format validation loop below until it passes.
10. Run the self-review below and fix any issues inline.
11. If the self-review changed the file, run `validate_doc.py` again before reporting success.
12. Report the path back to the user.

## Format Validation Loop

1. Save the edited PRD.
2. Run:
   `python3 scripts/validate_doc.py "<file path>"`
   Resolve `scripts/validate_doc.py` relative to this skill directory.
3. If validation fails:
   - read the error message
   - fix the PRD in place
   - save the file
   - run the validator again
4. Only move to self-review after the validator passes.

## Self-Review

After the PRD passes `validate_doc.py`, read it again with fresh eyes and check it against the user request and source material. This is a checklist you run yourself, not a separate reviewer or an extra pass you offer to the user.

**1. Scope coverage:** Skim the user's request, conversation constraints, and parent idea brief or parent PRD. Can you point to where each important requirement or constraint is represented in the PRD? Add anything important that is missing.

**2. Section consistency:** Do `Vision & Goals`, `In Scope`, `Out of Scope`, and `User Experience` all describe the same product surface without contradiction or scope drift? Fix any mismatch.

**3. Requirement quality:** Is each `FR-xx` concrete, distinct, implementable, and testable? Replace vague filler with specific behaviors.

**4. Patch correctness:** For `patch` PRDs, is the document clearly incremental to the parent PRD rather than a rewrite of the whole feature? If not, tighten it.

**5. Boilerplate scan:** Are `Risks` and `Dependencies` specific to this request rather than generic prose that could fit any PRD? Make them specific.

If you find issues, fix them inline and rerun `validate_doc.py` before reporting success. No need to announce a separate review pass to the user. Fix the document and move on.

## Gotchas

- Do not invent the file name or front matter by hand. Always start with `scripts/create_prd.py`.
- `parent` must match `role`: `main` uses an `idea-xxxxx-...` id, while `patch` uses a `prd-xxxxx-...` id.
- Keep the scaffold's exact top-level headings. Renaming or reordering them can break downstream validation.
- Replace every angle-bracket placeholder line like `<...>`. A PRD with scaffold placeholders still present is invalid.
- Keep `Functional Requirements` as checklist items using unique `FR-xx` ids. Do not switch to prose paragraphs.
- Use `--output-dir` only when the user explicitly asks for a different destination.
- Do not tell the user you can do an extra stricter review pass later. Self-review is already part of the default workflow.

## Generated File

By default the script saves to `docs/prd` in the current project. Use `--output-dir` only when
the user explicitly wants a different location.

- front matter with `id`, `type`, `role`, `status`, and `parent`
- the PRD document
- these sections:
  `One-line Summary`, `Vision & Goals`, `Actor`, `In Scope`, `Out of Scope`,
  `Functional Requirements`, `User Experience`, `Risks`, and `Dependencies`

Fill the generated file in place.
