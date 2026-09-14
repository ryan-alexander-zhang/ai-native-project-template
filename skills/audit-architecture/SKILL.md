---
name: audit-architecture
description: Whole-repo architecture audit by a fresh-context subagent — code structure against ARCHITECTURE.md and every active design and decision. Use when the user invokes /audit-architecture, before a feature-sized plan becomes resolved, or when 20+ PRs merged since the last audit report. Writes a docs/report; each finding becomes a doc revision, an issue, or a decision.
---

# Audit Architecture

The main session never audits; dispatch one general-purpose subagent with this
file and nothing else from the conversation.

## Inputs

- `ARCHITECTURE.md`, `CONTEXT.md`
- every `active` doc in `docs/design/`, `docs/decision/`, and `docs/quality/`
- the dependency graph from the `Architecture` gate tool (`CODE_QUALITY.md` §2),
  or the import list when the gate is unfilled
- `git log --stat` since the last `report-*-architecture-audit-*`; whole
  history when none exists

## Checks

One finding per line, each with `path:line` evidence.

1. Structure vs `ARCHITECTURE.md` §5 — building block missing, extra, or wired differently.
2. Code vs each `active` design and decision — behaviour that contradicts the doc; a decision without `enforced_by` whose rule is violated.
2b. Quality drivers — a `build`-stage `QS` whose `enforced_by` test is missing or not run by the gate; a `runtime` `QS` with no `active` operation doc implementing it; a design tactic (cache, replica, queue, retry, boundary) that names no `QS` in `implements`; a technology decision naming no `QS` in `motivated_by`.
3. Concept duplication — two identifiers for one `CONTEXT.md` term; a recurring identifier with no term.
4. Hotspots — files changed most since the last audit, with size and complexity trend.
5. Gate drift — raised threshold, new suppression, or a `CODE_QUALITY.md` §9 baseline moved up, without a `decision`.

## Output

`docs/report/report-<n>-architecture-audit-<YYYYMMDD>.md` per its `TEMPLATE.md`,
`active`; written even with zero findings. Per finding:

- doc stale — revision round on that doc (`docs/README.md`)
- code wrong — `docs/issue`
- choice needed — `docs/decision`

An open finding blocks the `plan` that triggered the audit (`AGENTS.md` §8).
