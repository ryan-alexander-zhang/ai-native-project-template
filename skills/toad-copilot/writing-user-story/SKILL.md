---
name: writing-user-story
description: >
  Save a user story in `docs/user-stories` as the current project's standard user story.
  Use this whenever the user wants a user story, acceptance criteria for a PRD functional
  requirement, or wants to break a PRD requirement into an executable story, even if they do not
  explicitly say "user story".
compatibility: Requires python3, a POSIX environment, and write access to the target project's `docs/user-stories`.
---

# Writing User Story

Use this skill when the user wants a user story saved in the current project's `docs/user-stories`.

## Defaults

- Use `main` unless the user explicitly asks for a patch.
- Use the related PRD id as `parent`, including when the role is `patch`.
- Use a `function_requirement_id` that matches a unique `FR-xx` item in the parent PRD.
- Use `status: draft` unless the user explicitly asks for `active` or `archived`.
- Derive a short slug from the story request.
- Keep the user story short and concrete.

## Workflow

1. Identify the parent PRD id.
2. Identify the matching `function_requirement_id` from that PRD.
3. If either is unclear, ask the user before writing the file.
4. Run:
   `python3 scripts/create_user_story.py "<slug or title>" --json --parent <prd-id> --function-requirement-id <FR-id> [--role main|patch] [--status draft|active|archived]`
   Resolve `scripts/create_user_story.py` relative to this skill directory.
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

By default the script saves to `docs/user-stories` in the current project. Use `--output-dir` only
when the user explicitly wants a different location.

- front matter with `id`, `type`, `role`, `status`, `parent`, and `function_requirement_id`
- the `# User Story` document
- these sections:
  `User Story`, `Acceptance Criteria`, and `Definition of Done`

Fill the generated file in place.
