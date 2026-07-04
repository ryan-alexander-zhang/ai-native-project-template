# Plans

This directory stores implementation plans.
Use `TEMPLATE.md` for front matter.

## Must Include

- Design
  - Use only what helps the plan. Examples: database table and field design, state machine, class diagram, sequence flow, etc.
  - When useful, link to a reusable design doc under `docs/design/`.

- Tasks
- Detailed Acceptance Path

Add more when useful.

## Exclude

- pure product requirements
- detailed task lists

## Guideline

1. Prefer Mermaid for design diagrams, such as ER, class, sequence, and state diagrams.
2. Keep tasks cohesive and low dependency. Tasks should be parallel when possible.
3. Acceptance should cover both: all split tasks are done, and the planned feature is tested and meets the target need.

## Note

If a plan is small, do not split it into `task/` dir.

A plan is a work item and uses the work-item status vocabulary. See
[docs/README.md](../README.md) for the shared definition.
