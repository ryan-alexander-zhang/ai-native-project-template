---
name: writing-architecture
description: >
  Maintain repo-root `ARCHITECTURE.md` as the current project's intended architecture summary.
  Use this whenever the user asks to create, refresh, sync, reconcile, review, or explain
  `ARCHITECTURE.md`, or wants a durable architecture overview synthesized from decision records, PRDs, specs,
  plans, user stories, ideas, and other durable repo docs. Preserve manual notes, adapt the
  template to the actual project, and do not let repo scanning dominate the architecture narrative.
  Use code and manifests only as supporting implementation evidence and drift detection. If a
  required section conflicts with the current implementation, stop that section and report the
  architecture drift explicitly.
compatibility: Requires a POSIX environment, `rg`, write access to repo-root `ARCHITECTURE.md`, read access to durable repo docs under `docs/`, and read access to repo structure/manifests for implementation checks.
---

# Writing Architecture

Use this skill when the user wants repo-root `ARCHITECTURE.md` created, refreshed, reconciled, or
reviewed as a durable guide for coding agents.

## Core Principle

`ARCHITECTURE.md` is a synthesis doc for the intended system architecture:

- decision records provide explicit architecture decisions when they exist
- PRDs, specs, plans, user stories, ideas, and other durable docs provide intended structure and
  boundaries when decision coverage is partial or absent
- code, manifests, and repo structure provide secondary evidence about implementation status and
  drift

Do not let the file drift into a generic template, a repo inventory, or a set of guesses. The goal
is not "what files exist today"; the goal is "what architecture this project is defining." Use repo
inspection to ground names and to detect drift, not to replace the documented design.

## Defaults

- Update repo-root `ARCHITECTURE.md` in place when it exists.
- When creating `ARCHITECTURE.md` for the first time, treat it as a document-first synthesis and
  read durable repo docs broadly before writing.
- Preserve manual-note regions exactly as written.
- Treat the bundled template as a section library, not a rigid schema.
- Omit sections that do not fit the repo or are unsupported by evidence.
- Prefer short, project-specific statements over general architecture prose.
- Do not block only because a decision record is missing if other durable project docs are sufficient for that
  section.

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

1. Determine mode before reading deeply:
   - `create`: repo-root `ARCHITECTURE.md` does not exist and the user asked to create it
   - `update`: `ARCHITECTURE.md` already exists, or the user asked to refresh, sync, reconcile, or review it
2. In `update` mode, read repo-root `ARCHITECTURE.md` first.
3. Read `docs/README.md` to understand repo documentation order and conventions.
4. Read durable design docs before inspecting implementation details. At minimum, inspect:
   - active `docs/decision`
   - relevant `docs/prd`
   - relevant `docs/spec`
   - relevant `docs/plan`
   - relevant `docs/user-story`
   - related `docs/idea`
   - other durable repo docs in `docs/` that define scope, boundaries, workflows, or constraints
5. Build a document-first view of the intended architecture before scanning the repo. For each
   candidate section, ask:
   - what architecture is the project explicitly deciding?
   - what components, boundaries, flows, and interfaces are implied by the docs?
   - which parts are settled versus still unspecified?
6. Inspect repo structure and manifests only after the intended architecture is clear. Prefer:
   - `rg --files`
   - top-level directories
   - `package.json`, `pyproject.toml`, `go.mod`, `Cargo.toml`
   - Docker files
   - `.github/`
   - relevant scripts or code paths only when needed to confirm naming, current implementation, or drift
7. Open the bundled template at `assets/architecture_template.md.tmpl` only after the architecture
   evidence map is clear.
8. Build an evidence map for each candidate section:
   - `decision record`: best source when present
   - `durable docs`: acceptable source when decision coverage is absent or incomplete
   - `docs + repo evidence`: safe to write, optionally noting implementation status
   - `documented-vs-implementation conflict`: block and escalate
   - `insufficient docs`: omit the section unless the user explicitly wants a partial/inferred draft
9. Write or update `ARCHITECTURE.md`:
   - preserve protected manual-note regions
   - synthesize the intended architecture from docs first
   - use repo evidence to anchor names and note implementation status, not to override the docs
   - remove irrelevant template examples and placeholders
   - omit unsupported sections rather than leaving filler
   - in `create` mode, start from the bundled template and immediately adapt it to the project; do not copy the template literally
   - in `update` mode, keep the refresh narrow: rewrite only the sections affected by changed architecture evidence
10. Report what changed, what was omitted, and what was blocked by architecture drift.

## Drift And Conflict Policy

When a required section is contradicted by the current implementation, stop updating that section
and report the architecture drift explicitly. Do not:

- rewrite the architecture around the repo scan result
- average documented design and implementation reality into vague prose
- pretend the implementation is the architecture source of truth
- quietly skip a critical section without reporting why

Missing decision records alone are not blockers when PRDs, specs, plans, user stories, or ideas provide
sufficient architecture evidence for the section.

Use this escalation format:

- `Blocked section:` `<section name>`
- `Documented architecture:` `<what decision records/PRD/specs/plans/user stories say>`
- `Observed implementation:` `<what code/manifests/repo structure show>`
- `Issue type:` `architecture drift` or `insufficient design evidence`
- `Required follow-up:` `<decision/spec/plan update or implementation change needed>`
- `Why blocked:` `<why the section cannot be responsibly synthesized>`

## Section Rules

- `Project Structure`: describe the intended system/module/package layout from docs first. Use the
  real repo tree only to confirm implementation status or drift. Never leave example directories
  like `backend/` or `frontend/` unless the architecture docs actually call for them.
- `High-Level System Diagram`: prefer documented component boundaries and flows. Use repo evidence
  only to label current implementation status.
- `Core Components`: describe intended components, packages, services, or modules from durable docs,
  even when some are not implemented yet.
- `Data Stores`: include stores and durable state mechanisms that are architecturally intended in
  the docs, then check whether the repo matches.
- `External Integrations / APIs`: include integrations that the architecture docs define, then
  confirm whether they are implemented.
- `Deployment & Infrastructure`: high risk for invention. Require strong durable-doc evidence; repo
  config alone is not enough to redefine the section.
- `Security Considerations`: include documented security mechanisms and constraints, then validate
  whether the implementation appears aligned.
- `Development & Testing Environment`: this section can rely more heavily on manifests, scripts, CI,
  and test layout because it is inherently implementation-facing.
- `Implementation Status` or `Drift Notes`: include only when useful to separate the intended
  architecture from the repo's current state.
- `Future Considerations / Roadmap`: omit unless a durable doc explicitly supports it.
- `Glossary / Acronyms`: preserve existing manual content and avoid invented definitions.

## Output Contract

When the update succeeds, report:

- path updated
- sections updated
- sections preserved
- sections omitted

When blocked, also report:

- blocked sections
- architecture drift or missing durable-doc topics

## Gotchas

- `ARCHITECTURE.md` is first in the repo read order for agents, so weak guesses here create broad downstream errors.
- First-time creation is document-first, so do not skip PRDs, specs, plans, user stories, and ideas when they define the architecture.
- Active decision records outrank template convenience, but missing decision records do not force a block when other durable docs are sufficient.
- Code alone can describe implementation reality, but it must not replace the intended architecture defined in the docs.
- When docs and repo disagree, the correct response is to surface drift, not to rewrite the architecture around the current codebase.
