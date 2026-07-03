# Commit Guide

## Scope

- Keep one logical change per commit.
- Split unrelated changes into separate commits.
- Stage only the files that belong to the change.

## Message

- Format: `<type>(<scope>): <description>`
- Scope is optional.
- Keep the description lowercase and do not end it with a period.
- Keep the first line short and precise.

## Types

- `feat`: new behavior
- `fix`: bug fix
- `docs`: documentation only
- `refactor`: internal change without intended behavior change
- `test`: test-only change
- `chore`: maintenance or repo housekeeping

## Rules

- Review the staged diff before committing.
- Do not commit secrets, generated noise, or unrelated local changes.

## Hooks

This repo ships a `pre-commit` hook that blocks committing docs still in `draft`
(see the Document Status Workflow in [AGENTS.md](AGENTS.md)). Enable it once per
clone:

```bash
git config core.hooksPath .githooks
```

To intentionally commit work in progress, bypass with `git commit --no-verify`.
