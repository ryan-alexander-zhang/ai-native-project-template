# Development

## Purpose

This file defines the minimum development workflow standard for this repo.

Use it to decide:
- how work should start
- what steps are required before changes are done
- how to keep scope under control

## Development Pattern

Keep development simple:
- understand the requested behavior before editing
- prefer the smallest change that solves the problem
- reuse existing patterns before adding new ones
- verify the changed behavior with the smallest useful check
- keep tests and docs aligned with the change

## Development Stages

### Understand

Use this stage to confirm the goal, constraints, and affected boundaries.

Understand stage should:
- identify the files and interfaces that matter
- separate facts from assumptions
- avoid silent scope expansion

### Implement

Use this stage to make the smallest change that delivers the requested behavior.

Implement stage should:
- keep unrelated code untouched
- match existing style and structure
- remove only unused code created by the change

### Verify

Use this stage to prove the change and check that scope stayed focused.

Verify stage should:
- run the smallest relevant checks first
- inspect the diff before completion
- confirm the requested behavior is complete

### Record

Use this stage when the change also needs durable docs, notes, or decisions.

Record stage should:
- update docs when behavior, workflow, or contract changed
- record decisions in the correct place
- keep long-term documentation consistent

## Development Guides

- [TESTING.md](TESTING.md): test expectations and completion bar for code changes.
- [DOCUMENT.md](DOCUMENT.md): documentation rules and document management flow.

## Development Matrix

| Change type | Minimum requirement |
| --- | --- |
| Docs-only change | Follow `DOCUMENT.md` and verify links, examples, and location. |
| Small code change | Make the smallest useful change and run the smallest relevant checks. |
| Behavior change | Update implementation, tests, and any affected docs together. |
| Public contract or workflow change | Update implementation, tests, and durable documentation together. |
| Refactor with no intended behavior change | Keep behavior unchanged, keep tests green, and keep the diff narrowly scoped. |
| Bug fix | Add or update a regression test and verify the original failure is covered. |

## Definition of Done

A change is done only when all of these are true:

- the requested behavior is complete
- the change scope stays focused
- the relevant checks pass
- the relevant docs are updated when needed
- no known regression is left behind
