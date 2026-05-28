# Code Style

## Purpose

This file defines the minimum code style standard for this repo.

Use it to decide:
- how code should read
- how naming and structure should stay consistent
- when a style-sensitive change is done

## Style Pattern

Keep code style simple:
- prefer clarity over cleverness
- follow existing local patterns before introducing new ones
- keep naming explicit and consistent
- keep formatting stable and predictable
- keep comments short and useful

## Style Areas

### Naming

Use clear, consistent names for files, modules, functions, types, and variables.

### Structure

Keep related logic together and keep code organization easy to follow.

### Formatting

Use the canonical formatting rules and avoid manual style drift.

### Comments

Write comments only when they add meaning that the code does not already show.

### Consistency

Match the surrounding code style unless there is a clear approved reason to change it.

## Style Matrix

| Change type | Minimum requirement |
| --- | --- |
| New code | Match the existing naming, structure, and formatting rules. |
| Edited code | Keep the touched area consistent without reformatting unrelated code. |
| New abstraction | Use only when it improves clarity and fits the existing style. |
| Comment change | Keep comments precise, necessary, and aligned with behavior. |
| Style cleanup | Keep the scope narrow and avoid mixing style-only edits with behavior changes. |

## Definition of Done

A style-sensitive change is done only when all of these are true:

- the code follows the local style rules
- naming and structure are consistent
- formatting drift was not introduced
- unrelated style churn was avoided
