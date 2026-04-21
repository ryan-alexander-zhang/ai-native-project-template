---
name: writing-architecture
description: >
  Maintain repo-root `ARCHITECTURE.md` as the current project's agent-facing architecture summary.
  Use this whenever the user asks to create, refresh, sync, reconcile, review, or explain
  `ARCHITECTURE.md`, or wants a durable architecture overview grounded in active ADRs, repo docs,
  code structure, and manifests. Preserve manual notes, adapt the template to the actual repo, and
  do not invent unsupported architecture. If active ADRs are missing or conflict with
  implementation for a required section, stop and tell the user which ADR must be written or
  updated before continuing.
compatibility: Requires a POSIX environment, `rg`, and write access to repo-root `ARCHITECTURE.md` plus read access to `docs/adrs`.
---

# Writing Architecture

Use this skill when the user wants repo-root `ARCHITECTURE.md` created, refreshed, reconciled, or
reviewed as a durable guide for coding agents.

## Core Principle

`ARCHITECTURE.md` is a synthesis doc:

- active ADRs provide authoritative `why`
- repo docs provide documented conventions
- code, manifests, and repo structure provide current `what`

Do not let the file drift into a generic template or a set of guesses. When evidence is missing for
an architectural section, stop and escalate instead of inventing a story.

## Defaults

- Update repo-root `ARCHITECTURE.md` in place when it exists.
- Preserve manual-note regions exactly as written.
- Treat the bundled template as a section library, not a rigid schema.
- Omit sections that do not fit the repo or are unsupported by evidence.
- Prefer short, repo-specific statements over general architecture prose.

## Protected Manual Notes

Preserve content inside explicit markers unchanged:

```md
<!-- manual-notes:start -->
...
<!-- manual-notes:end -->
```

If the file has hand-written notes outside protected regions, preserve them when clearly possible,
but do not guess which prose is user-owned. Encourage the user to move important notes into the
protected markers for future safe updates.

## Workflow

1. Read repo-root `ARCHITECTURE.md` first when it exists.
2. Read `docs/README.md` to understand repo documentation order and conventions.
3. Read active ADRs in `docs/adrs` before inferring architecture from code. Focus on `status: active`.
4. Inspect repo structure and manifests. Prefer:
   - `rg --files`
   - top-level directories
   - `package.json`, `pyproject.toml`, `go.mod`, `Cargo.toml`
   - Docker files
   - `.github/`
5. Open the bundled template at `assets/architecture_template.md.tmpl` only after the repo context is clear.
6. Build an evidence map for each candidate section:
   - `ADR + code`: safe to write with rationale
   - `code only`: safe to describe as current implementation
   - `missing decision`: block and escalate
   - `conflict`: block and escalate
7. Update `ARCHITECTURE.md` in place:
   - preserve protected manual-note regions
   - rewrite sections to match the repo
   - remove irrelevant template examples and placeholders
   - omit unsupported sections rather than leaving filler
8. If the file does not exist and the user asked to create it, start from the bundled template and immediately adapt it to the repo. Do not copy the template literally.
9. Report what changed and what was blocked.

## Conflict And Missing-Decision Policy

When a required section depends on an architectural decision that is missing or contradicted by an
active ADR, stop updating that section and tell the user exactly what ADR is needed. Do not:

- reconcile the conflict by guesswork
- average code reality and ADR intent into vague prose
- mark the section as "best practice" or similar filler
- quietly skip a critical section without reporting why

Use this escalation format:

- `Blocked section:` `<section name>`
- `Observed evidence:` `<what code/docs/manifests show>`
- `ADR issue:` `missing` or `stale/conflicting`
- `Required ADR:` `<specific ADR topic to write or update>`
- `Why blocked:` `<why the section cannot be responsibly synthesized>`

## Section Rules

- `Project Structure`: derive from the real repo tree. Never leave example directories like
  `backend/` or `frontend/` unless they actually exist.
- `High-Level System Diagram`: write only when component boundaries are materially inferable from
  docs and code.
- `Core Components`: describe actual components, packages, services, or major docs-backed modules.
- `Data Stores`: include only storage systems evidenced in code, config, or ADRs.
- `External Integrations / APIs`: include only real integrations found in docs, config, or code.
- `Deployment & Infrastructure`: high risk for invention. Require strong evidence or an active ADR.
- `Security Considerations`: include concrete mechanisms only, not generic security claims.
- `Development & Testing Environment`: infer from manifests, scripts, CI, and test layout.
- `Future Considerations / Roadmap`: omit unless a durable doc or ADR explicitly supports it.
- `Glossary / Acronyms`: preserve existing manual content and avoid invented definitions.

## Output Contract

When the update succeeds, report:

- path updated
- sections updated
- sections preserved
- sections omitted

When blocked, also report:

- blocked sections
- missing or stale ADR topics

## Gotchas

- `ARCHITECTURE.md` is first in the repo read order for agents, so weak guesses here create broad downstream errors.
- Active ADRs outrank template convenience.
- Code alone can describe implementation reality, but it cannot justify intent when a section clearly depends on an architectural choice.
- If the repo has no ADRs, keep the file narrow and factual. Escalate any section that needs a missing decision.
