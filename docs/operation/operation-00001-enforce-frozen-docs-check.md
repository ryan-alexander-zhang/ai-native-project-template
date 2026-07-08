---
id: operation-00001-enforce-frozen-docs-check
type: operation
role: main
status: active
parent: decision-00003-docs-system-owned-by-main
---

# Make `frozen-docs` a required check on lang branches

Runbook for enforcing [[decision-00003-docs-system-owned-by-main]] on the
server side: the docs-system skeleton is owned by main, and `lang/**` branches
must not modify it. The `frozen-docs` CI job
(`.github/workflows/frozen-docs.yml`) reports the violation; this runbook makes
that report block merges.

## Prerequisite: let the check appear once

GitHub can only require a status check that has already reported at least once.

1. Push the workflow to the default branch: `git push origin main`.
2. Once a `lang/*` branch exists and is pushed, `frozen-docs` runs for the
   first time.

The check's **context name is `frozen`** — the `jobs.frozen` id in the
workflow (it has no `name:`, so the id is used). This is the value to select
when configuring the requirement.

## Option A — Rulesets (recommended)

Settings → Rules → **Rulesets** → New branch ruleset:

1. **Target branches** → Add target → *Include by pattern* → `lang/**`.
2. Enable **Require status checks to pass** → Add checks → select `frozen`
   (if it is not listed, it has not run yet — trigger it once first).
3. Enable **Require a pull request before merging** (see the limitation below).
4. Enforcement status = **Active** → Create.

## Option B — Classic branch protection

Settings → Branches → Add branch protection rule:

- Branch name pattern: `lang/*` (use `lang/**` for multi-segment names).
- Enable **Require status checks to pass before merging** → select `frozen`.
- Enable **Require a pull request before merging**.

## Option C — Command line (`gh api`, ruleset)

Requires `gh auth login` and admin on the repo.

```bash
gh api -X POST repos/ryan-alexander-zhang/ai-native-project-template/rulesets \
  -f name='freeze-docs-on-lang' \
  -f target='branch' \
  -f enforcement='active' \
  -F 'conditions[ref_name][include][]=refs/heads/lang/**' \
  -F 'conditions[ref_name][exclude][]=' \
  -f 'rules[][type]=pull_request' \
  -f 'rules[][type]=required_status_checks' \
  -F 'rules[][parameters][required_status_checks][][context]=frozen' \
  -F 'rules[][parameters][required_status_checks][][integration_id]=15368' \
  -F 'rules[][parameters][strict_required_status_checks_policy]=true'
```

`integration_id=15368` is the GitHub Actions app, disambiguating same-named
checks. `strict...=true` requires the branch to be up to date with its base
before merging.

## Limitation: required checks gate PRs, not direct pushes

A required check blocks **PR merges**, not a direct `git push`. Pushing straight
to `lang/java` still lands the commit; `frozen-docs` merely goes red afterward.
To close the bypass, also **require a pull request** (all three options above do)
and disallow bypass, so every change flows through a PR that cannot merge while
`frozen` is red.

The local `.githooks/pre-commit` guard is only an early reminder for direct
pushes and can be skipped with `--no-verify`. The server-side PR + required
check is the real gate.

## Verify

- Open a PR from a `lang/*` branch that edits a frozen file (e.g. any
  `docs/**/TEMPLATE.md`): `frozen` must fail and merge must be blocked.
- Open a PR that only adds a `docs/spec/*` instance doc: `frozen` must pass.
