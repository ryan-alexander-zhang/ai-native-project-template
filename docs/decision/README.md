# Decisions

Durable decision records. Front matter: `TEMPLATE.md`.

## Must Include

- business or product-shape choices that materially change scope, workflow, or operating model
- architecture decisions and structural trade-offs
- technology or tool selection decisions
- accepted or rejected options and why

Add more when useful.

## Good Fit

- the choice is expensive to reverse later
- multiple credible options existed
- future contributors would ask "why did we choose this?"
- the decision affects more than one file, workflow, or contributor

## Relations

- `motivated_by` — what created the need: an `analysis`, `report`, `spec`,
  `prd`, `idea`, or the `quality-<n>-QS-<i>.<k>` scenarios served. A technology
  or structure decision names the scenarios it serves (`QUALITY.md`); a trade
  between two quality requirements names both. A decision from a review
  conversation with no doc to cite omits the field and names the conversation
  in §1.
- `constrains` — the `prd` / `quality` / `spec` / `rule` / `design` / `plan` /
  `operation` docs the choice binds, minus any declaring
  `implements: [<this decision>]`. A doc later falling under an `active`
  decision is added here; reach metadata, not content, so no amendment.

## Provenance

- `decided_by` — `human` or `agent`; written only by autopilot runs
  (`AUTOPILOT.md`). Omit otherwise.

## Enforcement

- `enforced_by` — tests that fail when the choice is violated; run under the
  `Architecture` gate (`CODE_QUALITY.md` §2). Required on every decision
  binding code structure: dependency direction, module boundary, layering,
  banned API. Omit only with the reason in §4 "Negative".

## Revision

- Wording, links, `constrains` backfill, `enforced_by` paths: amend in place.
- The choice itself changes (a §2 row replaced, removed, narrowed, widened; a
  §4 consequence flips): new decision with `supersedes: [<old id>]`; old doc
  `archived` + `superseded_by: [<new id>]`, body untouched. Repoint
  `implements` / `constrains` / `enforced_by`; `check-docs-drift.sh` fails on
  any left behind.
- In doubt, supersede.

## Exclude

- temporary discussion or brainstorming notes
- routine implementation details with no lasting trade-off
- status updates
- test reports
