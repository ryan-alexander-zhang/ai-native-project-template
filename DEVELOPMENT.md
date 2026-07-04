# Development

## Purpose

This file defines the workflow stages, commands, and Definition of Done for
implementation work in this repo.

Behavioral principles (think before coding, simplicity, surgical changes,
goal-driven execution) live in [AGENTS.md](AGENTS.md) and are not repeated here.

Use this file to decide:
- what stage the work is in
- which commands and checks to run
- when a change is done

## Development Stages

### Understand

Confirm the goal, constraints, and affected boundaries.

- Identify the files and interfaces that matter.
- Separate facts from assumptions.
- Avoid silent scope expansion.

### Plan

Decide how before building.

- For a feature-sized `spec` (multiple files, real ordering or design choices),
  create or refresh a `docs/plan/` doc before implementing, then work it
  task-by-task. Follow the `idea -> prd -> spec -> plan` flow in
  [docs/README.md](docs/README.md).
- For small or localized changes, inline reasoning is enough — do not create a
  plan doc.

### Implement

Make the change.

- Keep unrelated code untouched.
- Match existing style and structure.
- Remove only unused code created by the change.

### Verify

Prove the change and check that scope stayed focused.

- Run tests per [TESTING.md](TESTING.md). Testing is part of Verify, not a
  separate phase after it.
- Inspect the diff before completion.
- Confirm the requested behavior is complete.

### Record

Use this stage when the change needs durable docs, notes, or decisions.

- Update docs when behavior, workflow, or contract changed.
- Record decisions in the correct place per [DOCUMENT.md](DOCUMENT.md).
- Keep long-term documentation consistent.

## Development Guides

- [ARCHITECTURE.md](ARCHITECTURE.md): system structure, boundaries, design constraints.
- [TESTING.md](TESTING.md): test levels, required tests, coverage.
- [COMMIT.md](COMMIT.md): commit scope, message rules, commit hygiene.
- [PR.md](PR.md): PR readiness, review flow, merge rules.
- [SECURITY.md](SECURITY.md): security rules, risk handling.
- [CODE_STYLE.md](CODE_STYLE.md): naming, formatting, consistency.
- [DOCUMENT.md](DOCUMENT.md): doc taxonomy and management.

## Commands

Canonical commands for this repo (fill in for the project):

- Setup: `<command>`
- Test: `<command>`
- Lint: `<command>`
- Build: `<command>`
- Run: `<command>`

## Development Matrix

| Change type | Minimum requirement |
| --- | --- |
| Docs-only change | Follow the document management workflow and verify links, examples, and location. |
| New feature (spec-sized) | Write or refresh a `docs/plan/` first, then implement task-by-task and test per the plan's acceptance path. |
| Small code change | Make the smallest useful change and run the smallest relevant checks. |
| Behavior change | Update implementation, tests, and any affected docs together. |
| Public contract or workflow change | Update implementation, tests, and durable documentation together. |
| Refactor with no intended behavior change | Keep behavior unchanged, keep tests green, and keep the diff narrowly scoped. |
| Bug fix | For any bug worth tracking, first create the matching `docs/issue` doc (first-principles root cause + failing-test reproduction, per `docs/issue/README.md`), then fix and keep its regression test green. |

## Definition of Done

A change is done only when all of these are true:

- the requested behavior is complete
- the change scope stays focused
- tracked or non-trivial bug fixes have a matching `docs/issue` record
- relevant tests pass and meet the [TESTING.md](TESTING.md) DoD
- relevant docs are updated per the [DOCUMENT.md](DOCUMENT.md) DoD
- code style meets the [CODE_STYLE.md](CODE_STYLE.md) DoD
- security-sensitive changes meet the [SECURITY.md](SECURITY.md) DoD
- no known regression is left behind
