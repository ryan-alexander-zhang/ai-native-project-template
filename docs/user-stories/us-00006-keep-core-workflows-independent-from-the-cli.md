---
id: us-00006-keep-core-workflows-independent-from-the-cli
type: us
role: main
status: draft
parent: prd-00001-project-bootstrap-copilot
function_requirement_id: FR-06
---

# User Story

As an independent developer, small engineering team member, or technical lead,
I want the core project and resource-management workflows to work without the CLI,
so that I can adopt other frontends later without changing the underlying workflow.

# Acceptance Criteria  
- [ ] AC-01: Core project setup and resource-management actions are available through non-CLI entry points.
- [ ] AC-02: The same workflows can be executed without invoking the CLI.
- [ ] AC-03: A new frontend can reuse the workflow layer without duplicating workflow logic.

# Definition of Done  
- [ ] The user story is implemented and verified in a non-CLI path.
- [ ] The workflow boundaries are documented for frontend reuse.
- [ ] Tests cover the non-CLI workflow path.
