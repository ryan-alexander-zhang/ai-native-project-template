# Decisions

This directory stores durable decision records.
Use `TEMPLATE.md` for front matter.

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

- `motivated_by` — what created the need for the choice: usually an `analysis`,
  `report`, `spec`, `prd`, or `idea`. A decision surfaced by a review
  conversation, with no doc to cite, omits the field (`docs/README.md`'s
  empty-field rule) and names the conversation in §1 instead.
- `constrains` — the `prd` / `spec` / `rule` / `design` / `plan` / `operation` docs the choice binds,
  minus any that already declare `implements: [<this decision>]`. When a new doc
  later falls under an `active` decision, add it here: the list is metadata about
  reach, not content, so updating it is not an amendment to the decision.

## Provenance

- `decided_by` — `human` or `agent`; written only by autopilot runs
  (`AUTOPILOT.md`) so a reviewer can list every choice an agent made in a
  human's place. Omit it otherwise.

## Enforcement

- `enforced_by` — the tests that fail when the choice is violated; they run
  under the `Architecture` gate (`CODE_QUALITY.md` §2). Required on every
  decision that binds code structure: dependency direction, module boundary,
  layering, banned API. Omit only with the reason in §4 "接受的代价"; a
  structural decision without either is unverified.

## Revision

- Wording, links, `constrains` backfill, `enforced_by` paths: amend in place.
- The choice itself changes (a §2 row replaced, removed, narrowed, or widened;
  a §4 consequence flips): new decision with `supersedes: [<old id>]`, old doc
  `archived` + `superseded_by: [<new id>]`, body untouched. Repoint `implements` / `constrains` / `enforced_by`
  to the new id; `check-docs-drift.sh` fails on any left behind.
- In doubt, supersede.

## Exclude

- temporary discussion or brainstorming notes
- routine implementation details with no lasting trade-off
- status updates
- test reports
