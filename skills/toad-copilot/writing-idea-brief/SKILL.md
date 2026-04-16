---
name: writing-idea-brief
description: >
  Save a product, feature, or startup idea into `docs/ideas` as the current project's standard
  idea brief.
  Use this whenever the user wants to save an idea, write up a concept, or put something in
  `docs/ideas`, even if they do not explicitly say "idea brief".
compatibility: Requires python3, a POSIX environment, and write access to the target project's `docs/ideas`.
---

# Writing Idea Brief

Use this skill when the user wants a new idea brief saved in the current project's `docs/ideas`.

## Defaults

- Use `draft` unless the user explicitly asks for `active` or `archived`.
- Use `parent: <id>` unless the user gives a specific parent.
- Derive a short slug from the idea title or concept.
- Keep the brief short and concrete.

## Workflow

1. Turn the user's idea into a short slug or title.
2. Run:
   `python3 scripts/create_idea_brief.py "<slug or title>" --json [--status draft|active|archived] [--parent <id>]`
   Resolve `scripts/create_idea_brief.py` relative to this skill directory.
3. Open the new file path returned by the script.
4. Replace every placeholder line with concise content based on the user's idea.
5. Keep the generated front matter and section structure. Do not rewrite the scaffold by hand.
6. Save the file and report the path back to the user.

## Generated File

By default the script saves to `docs/ideas` in the current project. Use `--output-dir` only when
the user explicitly wants a different location.

- front matter with `id`, `type`, `role`, `status`, and `parent`
- the `# Idea Brief` document
- these sections:
  `One-line Summary`, `Problem`, `Target User`, `Current Alternatives`, `Proposed Solution`,
  `Why Better`, `Why Now`, `Risks`, and `Decision`

Fill the generated file in place.
