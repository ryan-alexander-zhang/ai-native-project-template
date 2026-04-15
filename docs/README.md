# Docs

This directory stores long-term project documents.

## Front Matter

Every main doc and patch doc should start with:

```md
---
id: spec-00001-doc-front-matter
type: adr|idea|integration|memory|operation|plan|prd|record|spec|task
role: main|patch
status: draft|active
parent: <id>
---
```

Write the document description or comment after the front matter.

## Front Matter Rules

- `id` uses `<type>-<five-digit-number>-<slug>`, for example `spec-00001-doc-front-matter`.
- `type` should match the target folder:
  - `adrs/` -> `adr`
  - `ideas/` -> `idea`
  - `integrations/` -> `integration`
  - `memory/` -> `memory`
  - `operations/` -> `operation`
  - `plans/` -> `plan`
  - `prds/` -> `prd`
  - `records/` -> `record`
  - `specs/` -> `spec`
  - `tasks/` -> `task`
- `role: main` is the canonical document for a topic. `role: patch` extends that main document.
- `status: draft` is work in progress. `status: active` is the current live version.
- `role: patch` means `parent` is the id of the main document.
- Main document flow is `idea -> prd -> spec -> plan -> task` when the later stage exists.
- A main document in that flow should use the upstream main document id as `parent`. For example, a main `spec` should use the related `prd` id.

## Folders

- `ideas/`: early ideas
- `prds/`: product requirements
- `specs/`: engineering specs
- `plans/`: implementation plans
- `tasks/`: execution tasks
- `adrs/`: architecture decision records
- `integrations/`: third-party integration notes
- `operations/`: runbooks and operations docs
- `memory/`: reusable long-term knowledge
- `records/`: reports and process records
- `references/`: external references

## Read Order

1. `ARCHITECTURE.md`
2. `memory/`
3. `adrs/`
4. `specs/`
5. `plans/`
6. `tasks/`
7. `operations/`
8. `integrations/`
9. `records/`
10. `prds/`
11. `ideas/`
12. `references/`

## Rules

- Keep one main version for one topic.
- `specs` say what the system should do.
- `plans` say how to do it.
- Use `tasks` only for large plans.
- Write an ADR for major architecture choices.
- Keep long-term knowledge in `memory/`.
- Keep reports and evidence in `records/`.
