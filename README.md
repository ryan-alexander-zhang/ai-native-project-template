# AI-Native Project Template

This repository is a docs-first template for starting an AI-native software
project.

It gives you a small, opinionated operating baseline for humans and coding
agents:

- root workflow docs for architecture, development, testing, security, style,
  commits, and PRs
- a `docs/` taxonomy for durable product, engineering, and operational docs
- test-level guides that force each repo to choose its own unit, API, and E2E
  stack while keeping integration testing anchored on Testcontainers by default
- a `skills-lock.json` file for pinning external skill dependencies

This is not a full application starter. It does not assume a frontend stack,
backend stack, or deployment platform. You bring the runtime code and keep the
workflow and documentation structure.

## What To Customize First

Start here after creating a repo from this template:

1. Update [ARCHITECTURE.md](ARCHITECTURE.md) with the intended system shape.
2. Update [AGENTS.md](AGENTS.md) with repo-specific working rules for agents.
3. Fill in the commands and project choices in:
   - [DEVELOPMENT.md](DEVELOPMENT.md)
   - [TESTING.md](TESTING.md)
   - [UNIT_TESTING.md](UNIT_TESTING.md)
   - [INTEGRATION_TESTING.md](INTEGRATION_TESTING.md)
   - [API_TESTING.md](API_TESTING.md)
   - [E2E_TESTING.md](E2E_TESTING.md)
4. Use [docs/README.md](docs/README.md) to decide where durable docs belong.
5. Add your runtime code in the structure that fits the project.

## Repo Map

- [AGENTS.md](AGENTS.md): behavior rules for coding agents in this repo
- [ARCHITECTURE.md](ARCHITECTURE.md): top-level architecture summary
- [DEVELOPMENT.md](DEVELOPMENT.md): implementation workflow and Definition of Done
- [DOCUMENT.md](DOCUMENT.md): document management rules
- [TESTING.md](TESTING.md): test-level policy and testing Definition of Done
- [docs/README.md](docs/README.md): source of truth for the docs taxonomy
- [skills-lock.json](skills-lock.json): pinned external skill sources

## Suggested Adoption Flow

Use the template in this order:

1. Lock the working agreements at the repo root.
2. Capture product and engineering intent under `docs/`.
3. Add runtime code only after the architecture and workflow boundaries are
   clear enough for agents to follow.

That keeps the repo usable by both humans and agents before the implementation
surface grows.
