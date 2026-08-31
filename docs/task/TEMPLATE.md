---
id: task-00001-example-slug
type: task
status: draft|open|resolved|archived|wontfix
parent: <plan-id>                             # required: the plan this task belongs to
---

# Task: <the slice of the plan this task executes>

> One sentence: what is done when this task is done.

## Tasks

Executable steps — each one small enough to finish and check in one sitting.

- [ ] <step, naming the file or module it lands in>
- [ ] <step>

## Order and Dependencies

<Which steps are independent and can run in parallel, and which one waits on
which. Name the blocking artefact, not just the step number.>

| Step | Depends on | Can run in parallel with |
| --- | --- | --- |
| <T1> | — | <T2> |
| <T2> | <T1> | — |

## Subtasks

<Only when a step is still too large: split it here, or link the child task
docs that carry it. Delete this section when nothing needs splitting.>

- <T1.1> <…>

## Acceptance Checklist

- [ ] <every step above is done>
- [ ] <the behaviour this task delivers is tested — name the test>
- [ ] <the parent plan's acceptance path is unblocked for this slice>
