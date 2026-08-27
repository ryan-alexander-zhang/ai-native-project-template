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

## Exclude

- temporary discussion or brainstorming notes
- routine implementation details with no lasting trade-off
- status updates
- test reports
