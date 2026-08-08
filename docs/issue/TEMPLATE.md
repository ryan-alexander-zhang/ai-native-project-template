---
id: issue-00001-example-slug
type: issue
status: draft|open|resolved|archived|wontfix
blocks: [<id>, ...]                           # required: what this issue blocks or clarifies
---

# Issue: <what breaks, in one line>

> One sentence: the defect and who it hurts.

## 1. Problem

- Observed: <what happens>
- Expected: <what should happen, and what says so — spec/rule id, or the invariant>
- Trigger: <where and when it surfaced; the input or state that exposes it>

## 2. Impact

- Affected: <who or what, and how many — users, records, environments>
- Since: <date or release> · Still occurring: <yes|no>
- Severity: <why this ranks where it does — not just a label>

## 3. Root Cause (first principles)

1. Observed vs expected behaviour, stated as a divergence.
2. The smallest mechanism that makes them diverge — cite `file:line`.
3. The true root cause, and the symptoms it is *not*.

- Introduced by: <commit | PR | "pre-dates the repo">. Before that change the
  defect could not occur — if it could, the root cause above is wrong.

## 4. Scope (same-cause sweep)

The root cause is a mechanism, so it rarely has one call site. List every site
sharing it; an unswept issue fixes one instance and leaves the rest.

| Site | Same pattern | Affected | Action |
| --- | --- | --- | --- |
| `path/to/file.ext:NN` | yes | yes | fixed here |
| `path/to/other.ext:NN` | yes | no | unreachable because <reason> |

## 5. Reproduction (test-first)

1. Write a test that reproduces the problem and fails *for that reason*.
2. Apply the fix and make it pass.
3. Keep it as the regression guard.

- Failing test: `<path::test_name>` — fails with <the assertion or error>

If a failing test is not practical, record why, and the strongest verification
used instead.

## 6. Fix

- Change: <what was changed>
- Why this addresses the root cause and not the symptom: <one line>
- Alternatives rejected (optional): <option — why not>

## 7. Verification

- <the regression test from §5, now passing>
- <any wider check: suite, manual run, production signal>

## 8. Follow-through

What this fix leaves behind for the codebase:

- Detection gap: why the existing tests missed it, and the guard added beyond
  the single regression test — or why none is warranted.
- Doc verdict: **code was non-conformant** (docs unchanged), or **the doc was
  wrong or missing** → amend `<spec|rule id>` and add the covering GWT.
- Residual state: data or config already damaged by the defect needs a
  backfill / migration / manual correction — or `none`.

## Links

- Blocks: <ids — mirror of `blocks`, kept for readers>
- Related: <analysis / decision / record ids>
