# Commit Guide

## Scope

- One logical change per commit; unrelated changes split.
- Stage only the files of the change.

## Message

- `<type>(<scope>): <description>`; scope optional.
- Description lowercase, no trailing period; first line short and precise.

## Types

- `feat`: new behavior
- `fix`: bug fix
- `docs`: documentation only
- `refactor`: internal change without intended behavior change
- `test`: test-only change
- `chore`: maintenance or repo housekeeping

## Rules

- Review the staged diff first.
- No secrets, generated noise, or unrelated local changes.

## Hooks

The `pre-commit` hook blocks committing `draft` docs. Enable once per clone:

```bash
git config core.hooksPath .githooks
```

Work in progress on purpose: `git commit --no-verify`.
