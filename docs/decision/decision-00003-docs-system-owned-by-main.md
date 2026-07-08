---
id: decision-00003-docs-system-owned-by-main
type: decision
role: main
status: active
parent:
---

# The docs-system skeleton is owned by main; lang branches only consume it

## Context

Language templates live on `lang/<l>` branches (see `resolveRef` in the ainpt
CLI: `--ref` > `lang/<lang>` > `main`). A lang branch is the base template plus a
language/framework layer, and it is long-lived and published — `ainpt new
--lang X` records the branch's commit SHA in `.ainpt.json`, and `ainpt update`
3-way merges against that recorded base.

Two things must stay true at once:

- The **docs-system skeleton** — the process meta-docs and every `README.md` /
  `TEMPLATE.md` that defines a doc kind's lifecycle and blank form — must be
  identical across all languages, so the system stays coherent and is
  maintained in one place.
- A lang branch must still **use** that system while building the template:
  writing `docs/spec/*`, `docs/design/*`, etc. is exactly how the language
  template is designed. Instance documents are content, not skeleton.

An earlier framing froze all of `docs/**`, which wrongly blocked the instance
documents a lang branch legitimately creates.

## Decision

Main is the single owner of the docs-system skeleton. Lang branches consume it
and never modify it.

- **Frozen (main-only), listed in `.template-sync.json`:** the root process
  docs (`AGENTS.md`, `DOCUMENT.md`, `COMMIT.md`, `PR.md`, `REVIEW.md`,
  `CONTEXT_TEMPLATE.md`, `SECURITY.md`) and every `docs/**/README.md` and
  `docs/**/TEMPLATE.md`.
- **Free on a lang branch:** everything else — `docs/<kind>/` instance docs,
  language-flavored content docs (`CODE_STYLE.md`, `*_TESTING.md`,
  `ARCHITECTURE.md`), `README.md`, `template.json`, and source/build files. New
  files are always allowed; the rule only forbids modifying frozen paths.
- **Propagation:** skeleton changes are made on main and folded into each lang
  branch with `git merge` (never rebase), via `scripts/sync-docs.sh`. Rebase is
  rejected because it rewrites published history and breaks the SHAs recorded in
  `.ainpt.json`.
- **Enforcement:** the `frozen-docs` CI job fails any `lang/**` branch that
  changed a frozen path relative to its merge-base with main (merge-base, not
  main's tip, so main being ahead is not a failure). The `.githooks/pre-commit`
  hook mirrors this locally.
- If a `TEMPLATE.md` turns out to need per-language variation, that is a signal
  to make it language-neutral on main (e.g. optional sections) rather than to
  fork it — the skeleton's language-neutrality is guaranteed on main.

## Consequences

- `.template-sync.json` — the machine-readable `frozen` list, the single source
  of truth for both the CI job and the hook.
- `.github/workflows/frozen-docs.yml` — the enforcing gate on `lang/**`.
- `scripts/sync-docs.sh` — merges main into lang branches (optional `PUSH=1`).
- `.githooks/pre-commit` — local guard rejecting frozen-path edits on `lang/*`.
- Adding a language stays "a new `lang/<l>` branch" with no CLI change; the docs
  system is inherited and kept current by merging main.
- Trade-off accepted: keeping frozen files byte-identical means any wording fix
  to the skeleton is a main commit plus a sync pass, not an edit on the branch
  where it was noticed.
