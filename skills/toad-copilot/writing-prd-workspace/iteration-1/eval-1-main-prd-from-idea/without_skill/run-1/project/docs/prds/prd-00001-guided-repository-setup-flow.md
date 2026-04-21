---
id: prd-00001-guided-repository-setup-flow
type: prd
role: main
status: draft
parent: idea-00001-project-bootstrap-copilot
---

# Product Requirements Document

## Summary

Project Bootstrap Copilot should provide a guided repository setup flow for new projects. The flow should ask a small set of setup questions, preview the proposed repository structure, and generate a consistent baseline layout, starter documentation, and agent-facing context files.

## Problem

New projects often begin with inconsistent repository structure and weak default documentation. Teams copy old repositories, adapt shell scripts, or create files manually. This creates drift across projects and makes AI-assisted workflows less reliable because the repository context is incomplete or uneven.

## Target User

Developers and technical leads starting a new AI-assisted coding project.

## Background

The parent idea proposes a project bootstrap copilot that standardizes repository layout, core docs, and reusable agent context. This PRD narrows that idea to the first-run guided setup experience for a new repository.

## Goals

- Help a user set up a new project repository through a short guided flow.
- Produce a predictable initial repository structure with strong defaults.
- Generate the minimum starter documents needed for humans and agents to work effectively.
- Reduce manual setup work and copy-paste scaffolding.

## Non-Goals

- Full application code generation.
- CI/CD, infrastructure, or deployment setup.
- Deep framework-specific scaffolding beyond basic repository structure.
- Migration of mature existing repositories into the new format.
- Ongoing project maintenance after initial setup is complete.

## User Outcome

At the end of the flow, a user should have a new repository with an agreed baseline structure, starter docs, and project context files, plus a clear summary of what was created and what to do next.

## Primary User Flow

1. The user starts the bootstrap flow in a new or nearly empty repository.
2. The copilot asks a guided sequence of setup questions.
3. The copilot proposes a repository layout and starter file set.
4. The user reviews the plan before any files are written.
5. The copilot creates the selected structure and files.
6. The copilot shows a summary of generated outputs and recommended next steps.

## Guided Setup Inputs

The first version should collect only the inputs needed to generate a useful baseline:

- Project name.
- Short project description.
- Project type, such as application, library, or internal tool.
- Primary language or stack.
- Whether agent context files should be included.
- Whether product documentation folders should be created.

## Repository Outputs

The guided flow should be able to create a baseline set of outputs such as:

- Root-level onboarding and context files, such as `README.md` and `AGENTS.md`.
- Documentation directories for project planning, such as `docs/ideas`, `docs/prds`, and `docs/user-stories`.
- Any minimal placeholder files needed so the generated structure is immediately usable.

The exact output list may vary by project type, but the first version should stay opinionated and small.

## Functional Requirements

### FR-01 Guided setup start

The system must support starting a repository setup flow for a new project.

### FR-02 Guided question sequence

The system must collect the minimum required setup information through a step-by-step guided interaction instead of requiring the user to supply everything up front.

### FR-03 Opinionated defaults

The system must provide default answers and recommended repository structure so the user can complete setup quickly with minimal decisions.

### FR-04 Repository plan preview

Before writing files, the system must present a preview of the proposed folders and files to be created.

### FR-05 Baseline documentation generation

The system must generate starter documentation files needed for repository orientation and product planning.

### FR-06 Agent context generation

The system must support generating agent-facing context files when the user chooses that option.

### FR-07 Safe file creation

The system must avoid silently overwriting existing files. If a target file or folder already exists, the system must surface the conflict before proceeding.

### FR-08 Completion summary

After setup completes, the system must summarize what was created and identify immediate next steps for the user.

### FR-09 New-project focus

The initial version must optimize for new or nearly empty repositories and may reject or warn on repositories that already contain substantial structure.

### FR-10 Minimal first version

The initial version must keep the guided flow short and avoid optional branches that materially increase complexity without improving the baseline setup outcome.

## Success Metrics

- A user can complete the setup flow for a new project in under 10 minutes.
- A newly created repository includes the core structure and docs without manual file creation.
- Users can understand what was generated and what to do next from the completion summary alone.
- Teams using the flow produce more consistent repository layouts across new projects.

## Risks

- The scope could expand from repository setup into broad project generation.
- Too many setup questions could make the flow feel slow and heavy.
- Too many project-type branches could weaken the benefit of consistent defaults.
- Generated outputs may feel generic if the defaults are not strong enough.

## Open Questions

- What is the smallest default file set that still makes the repository immediately useful?
- Which project types should the first version support explicitly?
- Should the first version create placeholder content or only structure?
- How should the flow behave when the repository is partially initialized but not empty?

## Release Scope

### In Scope

- Guided first-run repository setup.
- Small set of setup prompts.
- Preview before write.
- Baseline docs and planning folders.
- Optional agent context files.
- Completion summary.

### Out of Scope

- Rich stack-specific generators.
- Build tooling configuration.
- Deployment configuration.
- Codebase migration.
- Team policy enforcement after setup.
