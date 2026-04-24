# Architecture Overview

This document summarizes the intended architecture of `toad-copilot` from the active idea, PRD,
ADR, user stories, and supporting repo evidence. It separates the planned product architecture
from the current repository implementation where those do not yet match.

<!-- manual-notes:start -->
<!-- Add human-maintained notes here. This block is preserved during updates. -->
<!-- manual-notes:end -->

## 1. Project Structure

The intended product architecture is a CLI-first application with two main logical boundaries:

- a reusable headless workflow core for project bootstrap and managed-resource operations
- a CLI interface that exposes those workflows for local terminal use

The current repository does not yet implement those boundaries as dedicated runtime packages. Its
checked-in structure is primarily documentation, repo-local skills, and helper scripts:

```text
.
├── docs/
│   ├── adrs/
│   ├── ideas/
│   ├── plans/
│   ├── prds/
│   ├── specs/
│   └── user-stories/
├── skills/toad-copilot/
│   ├── writing-adr/
│   ├── writing-architecture/
│   ├── writing-idea-brief/
│   ├── writing-prd/
│   └── writing-user-story/
├── .agents/skills/
├── scripts/
├── AGENTS.md
├── TESTING.md
└── skills-lock.json
```

## 2. High-Level System Diagram

```text
User
  |
  v
CLI MVP
  |
  v
Reusable bootstrap and resource-management core
  | \
  |  \--> Template and managed-resource definitions
  |
  \----> Target project filesystem

Future desktop/web/frontend adapters
  |
  v
Same reusable core
```

The durable docs define the CLI as the first interface and require the workflow layer to stay
independent so later frontends can reuse it without duplicating workflow logic.

## 3. Core Components

### Intended Product Components

- `Workflow core`: the headless layer that bootstraps a new local project, standardizes an
  existing local project, manages preset files such as `AGENTS.md`, and manages project-level
  skills and reusable resources.
- `CLI interface`: the MVP entry point for running bootstrap and resource-management workflows from
  the terminal with clear success or failure results.
- `Template and managed-resource library`: the reusable definitions the workflow applies locally,
  including standard directory structure, preset docs, and project-scoped skills or resources.

### Current Repository Components

- `Durable design docs`: the repo currently captures product and architecture intent in
  `docs/ideas`, `docs/prds`, `docs/adrs`, `docs/specs`, `docs/plans`, and `docs/user-stories`.
- `Repo-local writing skills`: `skills/toad-copilot/*` packages the skill instructions, templates,
  eval fixtures, and helper scripts used to maintain those durable docs.
- `Helper scripts and installer`: Python scripts under individual skills scaffold or validate docs,
  and `scripts/install_toad_copilot_skills.sh` links the project skills into `.agents/skills/`.

## 4. Data Stores

- `Target project filesystem`: the planned primary state boundary. The product is expected to
  create and update local directories, preset files, and project-level skills/resources in place.
- `Durable repo docs`: within this repo, architecture and product decisions are stored as Markdown
  under `docs/` and act as the source of truth for intended behavior.
- `Skill assets and eval fixtures`: templates and evaluation fixtures under
  `skills/toad-copilot/` are the reusable artifacts that support the current repo-local workflows.

No database, queue, remote sync system, or hosted persistence layer is defined in the active
architecture docs. Low-level storage design is intentionally still unspecified.

## 5. External Integrations / APIs

No end-user runtime integration is defined in the current idea, PRD, ADR, or user stories.

The current repository does reference externally sourced skills in `skills-lock.json`, but those
entries are development-time skill dependencies rather than product runtime integrations.

## 6. Security Considerations

- The MVP is explicitly local-first. Its main trust boundary is the user's local filesystem and
  shell environment rather than a hosted service boundary.
- Managed-file behavior should stay surgical. The user stories for preset files and existing-project
  bootstrap both require creating or standardizing managed resources without unrelated repo changes.
- No authentication, authorization, secrets-management, or hosted multi-tenant security model is
  currently documented. If those concerns enter scope later, they need explicit ADR or spec
  coverage instead of being inferred from implementation details.

## 7. Development & Testing Environment

- The current repo-native workflows assume a POSIX shell environment with `rg`, Python 3, and write
  access to repo docs and skill directories.
- Skills are authored under `skills/toad-copilot/` and exposed locally through `.agents/skills/`.
- The current automation surface is script-driven: per-skill Python helpers create, review, list,
  or validate durable Markdown docs.
- `TESTING.md` is the testing policy for this repo. It requires the lowest effective test level for
  a change, regression tests for bug fixes, and at least 90% line, branch, and function coverage
  for executable code changes. Docs-only changes require manual verification.

## 8. Implementation Status / Drift Notes

- `Documented target architecture`: a TypeScript-on-Node.js reusable core with a separate CLI MVP.
- `Observed repository state`: no `package.json`, `tsconfig.json`, or TypeScript runtime packages
  are checked in. The current repo is primarily durable docs, Markdown skills, Python helper
  scripts, a shell installer, and skill eval workspaces.
- `Current interpretation`: this repository currently acts as the architecture, documentation, and
  repo-local skill foundation for the planned product. The documented runtime architecture has been
  chosen, but the runtime package structure itself has not yet been implemented here.

## 9. Future Considerations / Roadmap

- Keep the workflow core independent from the CLI so future desktop, web, or other frontends can
  reuse the same logic.
- Revisit packaging and distribution only after the core and CLI boundaries exist concretely.
- Do not introduce a web framework into the MVP unless the project scope explicitly expands beyond
  the CLI-first release.

## 10. Project Identification

- `Project`: `toad-copilot`
- `Current product theme`: project bootstrap copilot for AI-heavy local repositories
- `Repository role today`: durable docs plus repo-local skills that define and exercise the
  intended product architecture
- `Last updated`: 2026-04-24

## 11. Glossary / Acronyms

- `ADR`: Architecture Decision Record
- `PRD`: Product Requirements Document
- `FR`: Functional Requirement
- `MVP`: Minimum Viable Product
