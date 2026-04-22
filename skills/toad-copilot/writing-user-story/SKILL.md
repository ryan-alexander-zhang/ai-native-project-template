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

- Require an explicit parent PRD id before generating any user story.
- Do not infer an FR from feature text or a plain-language description.
- When explicit FR ids are missing, list the parent PRD's FRs and ask the user to choose `all` or an explicit subset such as `FR-01, FR-03`.
- When the user chooses `all`, ask whether to run `sequential` or `parallel`.
- Use a planning pass to compute `FR -> role` before generating any files.
- Treat any existing story with the same `parent + function_requirement_id` as a duplicate and create the new story with `role: patch`.
- Use `status: draft` unless the user explicitly asks for `active` or `archived`.
- Keep each user story short and concrete.

## Workflow

1. Identify the parent PRD id.
2. If the parent PRD id is missing, ask the user to provide it before doing any other work.
3. Run:
   `python3 scripts/plan_user_stories.py --parent <prd-id>`
   Resolve `scripts/plan_user_stories.py` relative to this skill directory.
4. If the user did not provide explicit FR ids, list the available FR ids from the planner output and ask the user to choose `all` or an explicit subset like `FR-01, FR-03`.
5. If the user chooses `all`, ask whether to run `sequential` or `parallel`.
6. If the user provides an explicit subset, rerun:
   `python3 scripts/plan_user_stories.py --parent <prd-id> --select <comma-separated-fr-ids>`
7. If the user chooses `all`, rerun:
   `python3 scripts/plan_user_stories.py --parent <prd-id> --all`
8. If the planner reports any invalid FR ids, reject the whole selection and ask the user to choose again.
9. Show a short terminal-only execution plan such as `FR-01 -> main` and `FR-03 -> patch`.
10. Ask for confirmation before generating any files.
11. In sequential mode, generate each planned story one by one by running:
    `python3 scripts/create_user_story.py "<slug or title>" --json --parent <prd-id> --function-requirement-id <FR-id> --role <main|patch> [--status draft|active|archived]`
12. In parallel mode, spawn one worker sub-agent per planned FR. Each worker owns a single FR, uses the planned role from the execution plan, runs `create_user_story.py`, fills the scaffold, validates the result, and reports success or failure without reverting other workers' changes.
13. If one story fails, retry that story exactly once.
14. After the retry, continue processing the remaining planned FRs.
15. Report which stories succeeded and which stories still failed.

## Generated File

By default the script saves to `docs/user-stories` in the current project. Use `--output-dir` only
when the user explicitly wants a different location.

- front matter with `id`, `type`, `role`, `status`, `parent`, and `function_requirement_id`
- the `# User Story` document
- these sections:
  `User Story`, `Acceptance Criteria`, and `Definition of Done`

Use the planner to decide which FR ids to generate and which role each story should use before invoking the single-story scaffold generator.
