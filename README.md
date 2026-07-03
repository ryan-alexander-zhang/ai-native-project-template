# AI-Native Project Template

This repository is a docs-first template for starting an AI-native software
project.

It gives you a small, opinionated operating baseline for humans and coding
agents:

- root workflow docs for architecture, development, testing, security, style,
  commits, and PRs
- a project glossary template for keeping product, code, and docs vocabulary
  consistent
- a `docs/` taxonomy for durable product, engineering, and operational docs
- test-level guides that let each repo choose its own unit and E2E stack while
  pinning integration testing to Testcontainers and API testing to Bruno by
  default

This is not a full application starter. It does not assume a frontend stack,
backend stack, or deployment platform. You bring the runtime code and keep the
workflow and documentation structure.

## What To Customize First

Start here after creating a repo from this template:

1. Update [ARCHITECTURE.md](ARCHITECTURE.md) with the intended system shape.
2. Update [AGENTS.md](AGENTS.md) with repo-specific working rules for agents.
3. Create `CONTEXT.md` from [CONTEXT_TEMPLATE.md](CONTEXT_TEMPLATE.md) and
   record the first project terms that need consistent usage.
4. Fill in the commands and project choices in:
   - [DEVELOPMENT.md](DEVELOPMENT.md)
   - [TESTING.md](TESTING.md)
   - [UNIT_TESTING.md](UNIT_TESTING.md)
   - [INTEGRATION_TESTING.md](INTEGRATION_TESTING.md)
   - [API_TESTING.md](API_TESTING.md)
   - [E2E_TESTING.md](E2E_TESTING.md)
5. Use [docs/README.md](docs/README.md) to decide where durable docs belong.
6. Add your runtime code in the structure that fits the project.

## What To Customize vs Keep

**Language / framework — fill in per project:**

- [ARCHITECTURE.md](ARCHITECTURE.md) — tech stack, module structure, boundaries
- [DEVELOPMENT.md](DEVELOPMENT.md) — the setup / build / test / lint / run commands
- [UNIT_TESTING.md](UNIT_TESTING.md) — choose the unit framework
- [E2E_TESTING.md](E2E_TESTING.md) — choose the E2E framework
- [INTEGRATION_TESTING.md](INTEGRATION_TESTING.md) — Testcontainers is pinned; fill in commands and environment
- [API_TESTING.md](API_TESTING.md) — Bruno is pinned; fill in base URL, auth, and command

**Project / domain — fill in, but not language-driven:**

- [AGENTS.md](AGENTS.md) — repo-specific agent rules
- `CONTEXT.md` (from [CONTEXT_TEMPLATE.md](CONTEXT_TEMPLATE.md)) — domain glossary
- [REVIEW.md](REVIEW.md) — review checklist (starts empty)
- [THIRDPARTY.md](THIRDPARTY.md) — external reference-only sources

**Generic policy — keep as-is unless you are deliberately changing the way of working:**

- [CODE_STYLE.md](CODE_STYLE.md), [COMMIT.md](COMMIT.md), [PR.md](PR.md),
  [DOCUMENT.md](DOCUMENT.md), [SECURITY.md](SECURITY.md),
  [TESTING.md](TESTING.md) (policy only — the framework choices live in the
  `*_TESTING.md` guides), and the [docs/](docs/README.md) taxonomy

## Repo Map

- [AGENTS.md](AGENTS.md): behavior rules for coding agents in this repo
- [ARCHITECTURE.md](ARCHITECTURE.md): top-level architecture summary
- [CONTEXT_TEMPLATE.md](CONTEXT_TEMPLATE.md): format spec for the `CONTEXT.md`
  project glossary
- [DEVELOPMENT.md](DEVELOPMENT.md): implementation workflow and Definition of Done
- [DOCUMENT.md](DOCUMENT.md): document management rules
- [TESTING.md](TESTING.md): test-level policy and testing Definition of Done
- [REVIEW.md](REVIEW.md): project-specific review checklist (starts empty)
- [THIRDPARTY.md](THIRDPARTY.md): register of external reference-only sources
- [docs/README.md](docs/README.md): source of truth for the docs taxonomy

## Suggested Adoption Flow

Use the template in this order:

1. Lock the working agreements at the repo root.
2. Capture product and engineering intent under `docs/`.
3. Add runtime code only after the architecture and workflow boundaries are
   clear enough for agents to follow.

That keeps the repo usable by both humans and agents before the implementation
surface grows.
