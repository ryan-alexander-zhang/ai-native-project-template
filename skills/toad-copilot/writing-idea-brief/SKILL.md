---
name: writing-idea-brief
description: >
  Capture product, feature, or startup concepts as concise idea briefs in this repository's
  docs/ideas format. Use when the user wants to save, draft, write up, or turn a concept into an
  idea brief or idea document, including prompts like "save this idea", "write this up", or "put
  this in docs/ideas", even if they do not name the template explicitly.
compatibility: Requires python3 and write access to this repository's docs/ideas directory.
---

# Writing Idea Brief

Announce at start: "I'm using the writing-idea-brief skill to create the idea brief."

## Available scripts

- `python3 scripts/create_idea_brief.py <slug> --json [--parent <id>] [--status draft|active|archived]`
  Reserves the next idea number under a file lock and writes the full scaffolded document.
- `python3 scripts/validate_idea_brief.py <path>`
  Validates front matter, required headings, risk bullets, decision block, and leftover scaffold text.

## Defaults

- Use `status: draft` unless the user explicitly asks for `active` or `archived`.
- Keep `parent: <id>` when the user did not provide a parent and there is no clear upstream document.
- Keep the brief short and concrete. Prefer one to three sentences per section.
- If the input is vague, draft reasonable assumptions instead of blocking. Only ask a follow-up when the answer changes `status`, `parent`, or the requested structure.

## Workflow

1. Derive a short slug from the idea.
2. Run the create script with `--json` so the next ID is reserved and the scaffolded file path is returned in structured output.
3. Edit the created file in place and replace every scaffold prompt with real content.
4. Keep the required structure and order.
5. Run the validator script on the saved file.
6. If validation fails, fix the file and rerun the validator until it passes.
7. Report the saved path and mention any material assumptions you had to make.

## Required structure

Keep these front matter fields:

- `id`
- `type`
- `role`
- `status`
- `parent`

Keep these headings in this order:

- `# Idea Brief`
- `## One-line Summary`
- `## Problem`
- `## Target User`
- `## Current Alternatives`
- `## Proposed Solution`
- `## Why Better`
- `## Why Now`
- `## Risks`
- `## Decision`

Keep the decision block exactly as scaffolded unless the user explicitly asks for a different format.

## Gotchas

- The create script already writes the full scaffold. Do not recreate the document structure by hand unless the user asks for a different template.
- Remove every scaffold prompt line. The validator treats leftover prompt text as a failure.
- Keep the three risk bullets and make each one concrete.
- Do not invent a parent document unless there is a clear upstream brief, spec, or ADR the user referenced.
