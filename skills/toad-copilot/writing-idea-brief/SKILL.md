---
name: writing-idea-brief
description: Write and save a simple idea brief in docs/ideas using the project idea brief template and front matter. Use when the user wants to capture, draft, or save a new product idea as an idea brief. Before writing, run the bundled script to allocate the next idea document number under a file lock and create the markdown file.
---

# Writing Idea Brief

Announce at start: "I'm using the writing-idea-brief skill to create the idea brief."

## Workflow

1. Derive a short slug from the idea.
2. Run `python3 <SKILL_DIR>/scripts/create_idea_brief.py <slug> [--parent <id>]`.
3. Edit the created file in place and write the brief body.
4. Keep the document short and concrete.
5. Replace every prompt line with real content.
6. Keep `parent: <id>` only when the user did not provide a parent and there is no clear upstream document.
7. Save the result to `docs/ideas/<id>.md`.
8. Report the saved path.

## Rules

- Use the script before writing so the number is reserved under a file lock.
- The script only creates the file and front matter. Write the brief content yourself.
- Use `status: draft` by default. Only use `active` or `archived` when the user asks for it.
- Keep the structure below exactly.
- Do not add extra sections unless the user asks.

## Front Matter

```md
---
id: idea-<five-digit-number>-<slug>
type: idea
role: main
status: draft|active|archived
parent: <id>
---
```

## Template

```md
# Idea Brief

## One-line Summary
What is this product in one sentence?

## Problem
What is the core problem users are facing today?

## Target User
Who is most likely to experience this problem?

## Current Alternatives
How are they solving it today?

## Proposed Solution
How does my solution address the problem?

## Why Better
What are the advantages over existing alternatives?

## Why Now
Why is this worth building now?

## Risks
- Risk 1:
- Risk 2:
- Risk 3:

## Decision
- [ ] Continue
- [ ] Pause
- [ ] Drop
```
