# Docs

This directory stores long-term project documents.

## Front Matter

Every main doc and patch doc should start with:

```md
---
id: spec-00001-doc-front-matter
type: analysis|decision|idea|integration|memory|operation|plan|prd|record|spec|task|us
role: main|patch
status: draft|active|archived
parent: <id>
---
```

Write the document description or comment after the front matter.

`us` docs also include:

```md
function_requirement_id: <FR-id>
```

## Front Matter Rules

- `id` uses `<type>-<five-digit-number>-<slug>`, for example `spec-00001-doc-front-matter`.
- `type` should match the target folder:
  - `analysis/` -> `analysis`
  - `decision/` -> `decision`
  - `idea/` -> `idea`
  - `integration/` -> `integration`
  - `memory/` -> `memory`
  - `operation/` -> `operation`
  - `plan/` -> `plan`
  - `prd/` -> `prd`
  - `record/` -> `record`
  - `spec/` -> `spec`
  - `task/` -> `task`
  - `user-story/` -> `us`
- `role: main` is the canonical document for a topic. `role: patch` extends that main document.
- `status: draft` is work in progress. `status: active` is the current live version. `status: archived` is kept for history and is no longer the current live version.
- `role: patch` means `parent` is the id of the main document.
- Main document flow is `idea -> prd -> spec -> plan -> task` when the later stage exists.
- A main document in that flow should use the upstream main document id as `parent`. For example, a main `spec` should use the related `prd` id.
- A main `decision` should use the closest upstream main document that created the need for the choice. In this repo that is usually an `idea`, `prd`, or `spec`.
- A patch `decision` is a child of a main `decision`. Its `parent` must be the decision id it extends.
- `us` docs are child docs of PRDs. Their `parent` is always the PRD id and `function_requirement_id` must match a unique `FR-xx` item in that PRD.

## Folders

- `analysis/`: codebase and business analysis docs
- `idea/`: early ideas
- `prd/`: product requirements
- `decision/`: durable decision records
- `spec/`: engineering specs
- `plan/`: implementation plans
- `task/`: execution tasks
- `integration/`: third-party integration notes
- `operation/`: runbook and operations docs
- `memory/`: reusable long-term knowledge
- `record/`: reports and process records
- `user-story/`: user stories attached to PRD functional requirements
- `reference/`: external references

## Read Order

1. `ARCHITECTURE.md`
2. `memory/`
3. `analysis/`
4. `decision/`
5. `spec/`
6. `plan/`
7. `task/`
8. `operation/`
9. `integration/`
10. `record/`
11. `prd/`
12. `user-story/`
13. `idea/`
14. `reference/`

## Rules

- Keep one main version for one topic.
- `spec` says what the system should do.
- `plan` says how to do it.
- Use `task` only for large plans.
- Use `analysis` for exploratory codebase or business analysis that informs later docs.
- Write a decision record for major business, architecture, product-shape, or technology choices with real trade-offs.
- Keep long-term knowledge in `memory/`.
- Keep reports and evidence in `record/`.
