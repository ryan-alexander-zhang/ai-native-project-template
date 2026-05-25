---
id: prd-00001-project-bootstrap-copilot
type: prd
role: main
status: draft
parent: idea-00001-project-bootstrap-copilot
---

# One-line Summary
A CLI-first bootstrap copilot for setting up local projects for AI coding work.

# Vision & Goals
Make it fast and repeatable to create or standardize a repository with the files, docs, and local
resources needed for AI coding workflows.

# Actor
Independent developers and small engineering teams working locally with coding agents.

# In Scope
- Creating a standard local project structure.
- Managing preset files such as `AGENTS.md`.
- Managing project-level skills and reusable resources.

# Out of Scope
- Hosted product surfaces.
- Broad project-management features.
- Low-level execution design details.

# Functional Requirements
- [ ] FR-01: The product must bootstrap a new local project with the repo structure and durable docs it manages.
- [ ] FR-02: The product must standardize an existing local project by adding missing managed resources.
- [ ] FR-03: The product must manage preset project files used by AI coding workflows.
- [ ] FR-04: The product must manage project-level skills and reusable resources stored with the project.

# User Experience
The user runs a local CLI, chooses whether to create or standardize a project, and gets a
deterministic result without manually copying templates or context files.

# Risks
- Scope creep could blur bootstrap behavior with unrelated platform features.
- Filesystem differences could make the workflow unreliable.

# Dependencies
- A maintained library of project resources.
- Reliable local filesystem access.
