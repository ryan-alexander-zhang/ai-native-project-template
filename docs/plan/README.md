# Plans

This directory stores implementation plans.
Use `TEMPLATE.md` for front matter.

## Must Include

- Design — links to the [`design/`](../design/README.md) docs this plan builds.
  The design itself lives there, never inline here.
- Tasks
- Detailed Acceptance Path

Add more when useful.

## Relations

- `implements` — **required**: the `spec` and/or `design` this plan makes real, or
  the `report` whose findings a remediation plan works through. It is the only
  thing tying a plan to what it builds.
- A feature-sized plan reaching `resolved` needs a [`record`](../record/README.md)
  whose `verifies` lists the requirement ids it checked.

## Exclude

- pure product requirements
- detailed task lists

## Guideline

1. Keep tasks cohesive and low dependency. Tasks should be parallel when possible.
2. Acceptance should cover both: all split tasks are done, and the planned feature is tested and meets the target need.

## Note

If a plan is small, do not split it into `task/` dir.

A plan is a work item and uses the work-item status vocabulary. See
[docs/README.md](../README.md) for the shared definition.
