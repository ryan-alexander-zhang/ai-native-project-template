# Issues

This directory stores development issues.
Use `TEMPLATE.md` for front matter and section structure.

## Must Include

- problem — observed vs expected, and the trigger
- impact — who is affected, since when, whether it is still occurring
- root cause (first principles), traced to `file:line`, naming the change that introduced it
- scope — every site sharing that root cause, each marked affected or not
- reproduction — a failing test written before the fix
- fix or workaround
- verification result
- follow-through — detection gap, spec/rule verdict, residual state

Add more when useful.

## Relations

- `blocks` — **required**: the docs this issue blocks or clarifies. Usually a
  `task`, `plan`, `spec`, or `prd`, but a `decision` or `report` whose text the
  issue contradicts is just as valid. An issue that blocks nothing has no reader
  who needs it.

## Exclude

- long-term architecture decisions
- full implementation plans
- generic reference dumps

## Note

Use this for problems found during development and how they were resolved.

A root cause that explains only the reported symptom is not finished: it must
also say which change made the defect possible, and where else that mechanism
lives.

## Status Lifecycle

An issue is a work item, so it uses the work-item status vocabulary:

- `draft` - pre-triage, still being written up.
- `open` - tracked, not yet fixed or only partially fixed.
- `resolved` - fix applied and verified by the regression test or verification result.
- `wontfix` - deliberately not fixing, or the issue turned out invalid / overtaken by events.
- `archived` - only when the document itself is superseded; it does not mean "fixed".
