---
id: plan-00001-example-slug
type: plan
status: draft|open|resolved|archived|wontfix
implements: [<spec-id | rule-id | spec-<n>-FR-<i> | rule-<n>-BR-<i> | design-id | report-id>]  # required: what this plan makes real; item ids declare the delivery scope (rule-00001-BR-24)
---

# Plan: <what this plan delivers, in one line>

> One sentence: the slice this plan builds, and the decision or spec round it lands.

## Design

Links only — the design itself lives in [`design/`](../design/README.md), never
inline here.

- [<design-id>](../design/<design-id>.md) — §<n> <the parts this plan builds>
- [<design-id>](../design/<design-id>.md) — §<n> <the parts this plan builds>

## Tasks

<how the work splits: which tasks are independent and run in parallel, which
one waits on which, and which task closes the round.>

- **T1 — <name>** (<the FR/BR ids it delivers>): <what is built, named down to
  the module or file it lands in>
- **T2 — <name>** (<ids>): <…>
- **Tn — tests and acceptance**: cover every AC in the delivery scope; hold the
  quality gates (no threshold lowered); write the `record` whose `parent` points
  at this plan.

## Detailed Acceptance Path

1. <the check that the built thing works> → verify: <the command, exit code, or
   observation that settles it>
2. Every item in the delivery scope has a passing row in the record → verify:
   <where that is read off>
3. This plan passes `open → resolved` on the board → verify: the resolved gate
   clears (`rule-00001-BR-25`).

## Out of Scope (optional)

- <what this plan deliberately does not do, and where it is handled instead>
