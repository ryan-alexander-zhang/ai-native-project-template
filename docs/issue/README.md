# Issues

Development issues. Front matter and sections: `TEMPLATE.md`.

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

- `blocks` — **required**: the docs this issue blocks or clarifies — a `plan`,
  `spec`, `prd`, or a `decision` / `report` it contradicts. An issue blocking
  nothing has no reader. A defect no doc covers means a missing doc: write or
  amend the `spec` / `rule` / `quality` doc first (a blown measure with no
  scenario is a missing `QS`), then block it.

## Exclude

- long-term architecture decisions
- full implementation plans
- generic reference dumps

## Note

A root cause explaining only the reported symptom is unfinished: it names the
change that made the defect possible and every other site of that mechanism.

## Status Lifecycle

Work-item vocabulary: `draft` (pre-triage) · `open` (tracked, not fixed) ·
`resolved` (fix applied and verified) · `wontfix` (deliberately not fixing, or
invalid) · `archived` (document superseded; never "fixed").
