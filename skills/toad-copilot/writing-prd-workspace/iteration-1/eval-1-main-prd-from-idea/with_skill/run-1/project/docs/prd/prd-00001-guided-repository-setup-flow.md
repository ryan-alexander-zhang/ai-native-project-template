---
id: prd-00001-guided-repository-setup-flow
type: prd
role: main
status: draft
parent: idea-00001-project-bootstrap-copilot
---

# One-line Summary
A guided repository setup flow that helps developers launch new AI-assisted projects with a consistent repo layout, starter docs, and reusable agent context.

# Vision & Goals
Teams should not have to hand-build the same project skeleton every time they start a new repo. This product exists to replace ad hoc bootstrapping with a guided flow that turns a few setup decisions into a usable, predictable project structure.

Success means a new project can move from blank repo to an agreed starting layout in one guided session, with clear defaults, a review step before files are written, and generated artifacts that both humans and coding agents can rely on immediately.

# Actor
Primary actor: a developer or technical lead creating a new project repository.

Secondary actor: an AI coding agent that later reads the generated docs and repo structure to work effectively inside the project.

# In Scope
- A guided setup flow for net-new or minimally initialized repositories.
- Collection of core setup inputs such as project name, language or runtime, package manager, testing baseline, and documentation defaults.
- Selection of a recommended repository structure and starter artifact set based on those inputs.
- Creation of foundational files and folders such as core docs, project context, and working directories for future product planning.
- A review step that shows what will be created before files are written.
- Safe reruns that avoid silently replacing existing user-authored content.

# Out of Scope
- Full product scaffolding for every framework or deployment target.
- CI/CD setup, cloud provisioning, and secrets management.
- Ongoing project maintenance after the initial repository bootstrap is complete.
- Automatic generation of application business logic or feature code.
- Migration of mature existing repositories with complex custom structure.

# Functional Requirements
The product must guide a new-project setup session from initial questions through reviewed file creation.
- [ ] FR-01: The system must start a setup session inside the current repository and detect whether the repo is empty, minimally initialized, or already contains conflicting bootstrap artifacts.
- [ ] FR-02: The system must collect the minimum required setup inputs through a guided sequence, including project identity, preferred stack defaults, and whether standard planning docs should be created.
- [ ] FR-03: The system must map the collected inputs to a recommended repository layout and starter artifact set using opinionated defaults rather than requiring users to configure every detail manually.
- [ ] FR-04: The system must present a preview of the directories and files it plans to create or modify before applying changes.
- [ ] FR-05: The system must let the user confirm the plan and then generate the selected repository structure and starter context files in the current project.
- [ ] FR-06: The system must not overwrite existing non-generated content without an explicit user decision for each conflict.
- [ ] FR-07: The system must produce a completion summary that lists what was created, what was skipped, and the next recommended step for continuing project setup.

# User Experience
The experience should feel like a short guided bootstrap wizard in the terminal or agent chat. The user answers a small number of concrete setup questions, sees recommended defaults instead of a long blank form, and gets a clear preview before anything is written.

After confirmation, the flow should create the agreed files and folders, report any skipped items or conflicts, and leave the user with a repo that is immediately understandable. The interaction should optimize for speed and confidence, not maximum flexibility.

# Risks
- The scope could expand from repository setup into general project management tooling.
- Templates may become too rigid for teams with non-standard repo conventions.
- Asking too many questions could make the setup feel slower than copying an old repo.
- Poor conflict handling could reduce trust if existing files are overwritten or partially duplicated.

# Dependencies
- A maintained library of starter templates and default repo structures.
- Clear rules for which files are considered generated and safe to recreate.
- Write access in the target repository and a reliable way to inspect existing files before generation.
- Agreement on the minimum standard docs and context files that every new project should receive.
