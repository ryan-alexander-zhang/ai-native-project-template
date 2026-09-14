# AI-Native Project Template

A docs-first template for starting an AI-native software project: an
opinionated operating baseline for humans and coding agents.

- root workflow docs for architecture, development, testing, security, style,
  commits, and PRs
- a project glossary template
- a `docs/` taxonomy for durable product, engineering, and operational docs
- test-level guides: each repo picks its unit, E2E, performance, and resilience
  stack; integration testing is pinned to Testcontainers, API testing to Bruno
- a quality-requirements method (`QUALITY.md`, `docs/quality/`) placing
  non-functional requirements between the PRD and the architecture, each with a
  measured scenario and a verification stage
- `whiteboard.config.yaml`, the flow configuration the
  [persimmon](https://github.com/ryan-alexander-zhang/persimmon) docs whiteboard
  reads to render `docs/` as a board and drive review / promote / ask / co-write

Not an application starter: no frontend, backend, or deployment stack assumed.
You bring the runtime code.

## What To Customize First

1. [ARCHITECTURE.md](ARCHITECTURE.md): the intended system shape.
2. [AGENTS.md](AGENTS.md): repo-specific agent rules.
3. `CONTEXT.md` from [CONTEXT_TEMPLATE.md](CONTEXT_TEMPLATE.md): first project terms.
4. Commands and project choices in the fill-in guides below.
5. [docs/README.md](docs/README.md) decides where durable docs belong.
6. Runtime code, only after the architecture and workflow boundaries are clear
   enough for agents to follow.

## What To Customize vs Keep

**Language / framework — fill in per project:**

- [ARCHITECTURE.md](ARCHITECTURE.md) — tech stack, module structure, boundaries
- [DEVELOPMENT.md](DEVELOPMENT.md) — setup / build / test / lint / run commands
- [UNIT_TESTING.md](UNIT_TESTING.md), [E2E_TESTING.md](E2E_TESTING.md) — choose the framework
- [PERFORMANCE_TESTING.md](PERFORMANCE_TESTING.md) — load tool, scenario profiles
- [RESILIENCE_TESTING.md](RESILIENCE_TESTING.md) — fault-injection tool, experiments
- [INTEGRATION_TESTING.md](INTEGRATION_TESTING.md) — Testcontainers pinned; commands and environment
- [API_TESTING.md](API_TESTING.md) — Bruno pinned; base URL, auth, command
- [CODE_QUALITY.md](CODE_QUALITY.md) — build-failing gates and tuned thresholds; metrics and SOP stay

**Project / domain — fill in, not language-driven:**

- [AGENTS.md](AGENTS.md) — repo-specific agent rules
- `CONTEXT.md` (from [CONTEXT_TEMPLATE.md](CONTEXT_TEMPLATE.md)) — domain glossary
- [REVIEW.md](REVIEW.md) — review checklist (starts empty)
- [THIRDPARTY.md](THIRDPARTY.md) — external reference-only sources
- [ACCEPTANCE.md](ACCEPTANCE.md) — derivation rules stay; fill in this domain's omissions

**Generic policy — keep unless deliberately changing the way of working:**

- [CODE_STYLE.md](CODE_STYLE.md), [COMMIT.md](COMMIT.md), [PR.md](PR.md),
  [DOCUMENT.md](DOCUMENT.md), [SECURITY.md](SECURITY.md), [QUALITY.md](QUALITY.md),
  [TESTING.md](TESTING.md) (policy; framework choices live in `*_TESTING.md`),
  and the [docs/](docs/README.md) taxonomy

## Docs Whiteboard

[persimmon](https://github.com/ryan-alexander-zhang/persimmon) renders `docs/`
and drives the workflow; install once, point at any repo from this template.
Here stay [whiteboard.config.yaml](whiteboard.config.yaml), which the board
reads, and `.whiteboard/`, its git-ignored local state.

## Repo Map

- [AGENTS.md](AGENTS.md): behavior rules for coding agents
- [ARCHITECTURE.md](ARCHITECTURE.md): architecture summary
- [CONTEXT_TEMPLATE.md](CONTEXT_TEMPLATE.md): format of the `CONTEXT.md` glossary
- [DEVELOPMENT.md](DEVELOPMENT.md): implementation workflow and Definition of Done
- [DOCUMENT.md](DOCUMENT.md): document management rules
- [AUTOPILOT.md](AUTOPILOT.md): unattended idea-to-PR run
- [ACCEPTANCE.md](ACCEPTANCE.md): deriving the acceptance a requirement owes
- [QUALITY.md](QUALITY.md): quality requirements entry point; routes to [QUALITY_PROFILE.md](QUALITY_PROFILE.md) (profile catalogue) and [QUALITY_SCENARIOS.md](QUALITY_SCENARIOS.md) (writing scenarios)
- [TESTING.md](TESTING.md): test-level policy and testing Definition of Done
- [CODE_QUALITY.md](CODE_QUALITY.md): quality gates and refactoring order
- [REVIEW.md](REVIEW.md): project-specific review checklist (starts empty)
- [THIRDPARTY.md](THIRDPARTY.md): register of external reference-only sources
- [docs/README.md](docs/README.md): source of truth for the docs taxonomy
