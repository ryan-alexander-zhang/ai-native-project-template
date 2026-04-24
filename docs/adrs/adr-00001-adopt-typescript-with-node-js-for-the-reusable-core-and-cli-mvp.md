---
id: adr-00001-adopt-typescript-with-node-js-for-the-reusable-core-and-cli-mvp
type: adr
role: main
status: draft
parent: prd-00001-project-bootstrap-copilot
---

# Adopt TypeScript with Node.js for the reusable core and CLI MVP

## Context
- Problem / trigger: `prd-00001-project-bootstrap-copilot` defines a CLI-first MVP for local project bootstrap and resource management, while requiring the workflow core to remain reusable outside the CLI.
- Decision drivers: keep the MVP focused on local CLI use, support filesystem and process-heavy workflows, preserve a clean boundary between workflow logic and interface code, move quickly on template and document management, and avoid introducing web application concerns before a web frontend is in scope.
- Options considered:
  - TypeScript with Node.js for a reusable core plus a CLI package, while deferring any web framework.
  - Go for both the core and CLI to optimize for a single-binary distribution model.
  - Next.js as an early application framework so a future web frontend is present from the start.

## Decision
- Chosen option: Build the MVP in TypeScript on Node.js, with a reusable core module and a separate CLI entry point. Do not adopt Next.js in the MVP.
- Why this option: This repo's MVP is a local CLI tool, not a web product. TypeScript with Node.js fits the immediate work well: filesystem access, path handling, process execution, Markdown and config manipulation, and testable module boundaries between the core and the CLI. It also keeps future frontend expansion open, because a later web or desktop interface can reuse the same workflow and domain code without forcing web framework concepts into the first release. We accept the downside that distribution is less convenient than a Go binary and may require a Node.js runtime or a packaging step.

## Consequences
- Positive: The MVP can stay small and CLI-focused while still enforcing a reusable core. One language can cover the core, CLI, and a later web frontend if one is added. The stack also aligns well with document, template, and config-heavy workflows.
- Negative: The CLI is not as simple to ship as a standalone Go binary. Users may need Node.js installed unless packaging is added later. Deferring Next.js also means there is no early validation of a web interface.
- Follow-up: Define package boundaries between the core and CLI before implementation begins. Choose the specific CLI tooling and test setup within the TypeScript ecosystem. Revisit packaging strategy and whether to add a Next.js frontend only when a web interface becomes an approved scope item.
