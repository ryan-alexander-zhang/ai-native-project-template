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
  - `analyses/` -> `analysis`
  - `decisions/` -> `decision`
  - `ideas/` -> `idea`
  - `integrations/` -> `integration`
  - `memory/` -> `memory`
  - `operations/` -> `operation`
  - `plans/` -> `plan`
  - `prds/` -> `prd`
  - `records/` -> `record`
  - `specs/` -> `spec`
  - `tasks/` -> `task`
  - `user-stories/` -> `us`
- `role: main` is the canonical document for a topic. `role: patch` extends that main document.
- `status: draft` is work in progress. `status: active` is the current live version. `status: archived` is kept for history and is no longer the current live version.
- `role: patch` means `parent` is the id of the main document.
- Main document flow is `idea -> prd -> spec -> plan -> task` when the later stage exists.
- A main document in that flow should use the upstream main document id as `parent`. For example, a main `spec` should use the related `prd` id.
- A main `decision` should use the closest upstream main document that created the need for the choice. In this repo that is usually an `idea`, `prd`, or `spec`.
- A patch `decision` is a child of a main `decision`. Its `parent` must be the decision id it extends.
- `us` docs are child docs of PRDs. Their `parent` is always the PRD id and `function_requirement_id` must match a unique `FR-xx` item in that PRD.

## Folders

- `analyses/`: codebase and business analysis docs
- `ideas/`: early ideas
- `prds/`: product requirements
- `decisions/`: durable decision records
- `specs/`: engineering specs
- `plans/`: implementation plans
- `tasks/`: execution tasks
- `integrations/`: third-party integration notes
- `operations/`: runbooks and operations docs
- `memory/`: reusable long-term knowledge
- `records/`: reports and process records
- `user-stories/`: user stories attached to PRD functional requirements
- `references/`: external references

## Read Order

1. `ARCHITECTURE.md`
2. `memory/`
3. `analyses/`
4. `decisions/`
5. `specs/`
6. `plans/`
7. `tasks/`
8. `operations/`
9. `integrations/`
10. `records/`
11. `prds/`
12. `user-stories/`
13. `ideas/`
14. `references/`

## Rules

- Keep one main version for one topic.
- `specs` say what the system should do.
- `plans` say how to do it.
- Use `tasks` only for large plans.
- Use `analyses` for exploratory codebase or business analysis that informs later docs.
- Write a decision record for major business, architecture, product-shape, or technology choices with real trade-offs.
- Keep long-term knowledge in `memory/`.
- Keep reports and evidence in `records/`.
