# Issues

This directory stores development issues.
Use `TEMPLATE.md` for front matter.

## Must Include

- problem
- context or trigger
- reproduction (a failing test written before the fix)
- fix or workaround
- verification result

Add more when useful.

### Reproduction (test-first)

Every issue should first be reproduced by a failing test, then fixed:

1. Write a test that reproduces the problem and fails for that reason.
2. Apply the fix and make that test pass.
3. Keep the test as the regression guard and cite it in the verification result.

If a failing test is not practical, record the reason and the strongest
verification used instead.

## Exclude

- long-term architecture decisions
- full implementation plans
- generic reference dumps

## Note

Use this for problems found during development and how they were resolved.

## Status Lifecycle

An issue is a work item, so it uses the work-item status vocabulary:

- `draft` - pre-triage, still being written up.
- `open` - tracked, not yet fixed or only partially fixed.
- `resolved` - fix applied and verified by the regression test or verification result.
- `wontfix` - deliberately not fixing, or the issue turned out invalid / overtaken by events.
- `archived` - only when the document itself is superseded; it does not mean "fixed".
