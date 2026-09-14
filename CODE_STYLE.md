# Code Style

## Purpose

Minimum code style standard: how code reads, how naming and structure stay
consistent, when a style-sensitive change is done.

## Style Pattern

- clarity over cleverness
- existing local patterns before new ones
- explicit, consistent naming
- stable formatting; comments short and useful

## Style Areas

- **Naming** — clear, consistent names for files, modules, functions, types, variables.
- **Structure** — related logic together; organization easy to follow.
- **Formatting** — the canonical rules; no manual drift.
- **Comments** — only where they add meaning the code does not show.
- **Consistency** — match surrounding style unless there is an approved reason to change it.

## Style Matrix

| Change type | Minimum requirement |
| --- | --- |
| New code | Match existing naming, structure, formatting. |
| Edited code | Touched area consistent; no reformatting of unrelated code. |
| New abstraction | Only when it improves clarity and fits the existing style. |
| Comment change | Precise, necessary, aligned with behavior. |
| Style cleanup | Narrow scope; never mixed with behavior changes. |

## Definition of Done

- local style rules followed
- naming and structure consistent
- no formatting drift
- no unrelated style churn
