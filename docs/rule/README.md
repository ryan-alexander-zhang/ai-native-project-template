# Business Rules

This directory stores business rules.
Use `TEMPLATE.md` for front matter.

## Must Include

- applicability
- terms the rules are built on
- the rules, each numbered `rule-<n>-BR-<i>` and tagged with its kind
- acceptance, numbered `rule-<n>-AC-<i>.<k>`
- open questions — what is still undecided

Add more when useful.

### Rule kinds

Tag every rule. The kind decides what else it must state.

| Kind | States | Must also state |
| --- | --- | --- |
| Definition | how a value is derived | — |
| Constraint | what must never be true | the response when it is violated |
| Decision | which outcome applies to which case | a hit policy, and an otherwise row |

A Definition cannot be violated; it defines. A Constraint can, so a rule that
names no violation response leaves the implementer to invent one.

### Decision tables

1. Hit policy, taken from DMN: `UNIQUE` (exclusive rows, order irrelevant) or
   `FIRST` (ordered, first match wins). Prefer `UNIQUE` — non-overlap is
   checkable, first-match is not.
2. End a `FIRST` table with an explicit otherwise row, numbered like any other.
3. `—` means the column does not participate in that row. Never empty or false.

### Condition notation

Rule text and table cells are prose the whiteboard does not parse; precision is
the author's job, checked at review. Checklist items, not grammar:

1. Ranges use explicit interval notation with stated boundaries — `(30, 60]`,
   `>= 2` — never "over 30" or "about a month".
2. A calendar quantity (month, day) states its carry semantics in Terms before
   a rule uses it (e.g. "one month later: the same day next month; when that
   day does not exist, the last day of that month").
3. A quantified condition over a collection states its predicate and threshold
   ("count of unpaid invoices `>= 2`"), with the predicate's term defined in
   Terms.
4. A derived number states rounding direction, precision, and where rounding
   applies, in its owning Definition.
5. An input that can be missing is decided by some row — in a `UNIQUE` table an
   explicit row, in a `FIRST` table the otherwise row — never left to fall
   through silently.

### Acceptance

Every `BR` needs at least one example; an unreferenced rule is unverified.

## Machine-readable form (item grammar)

Documents in this folder are parsed in the form below; a line that does not fit
is a parse error:

- A rule declaration is a whole line, in one of two forms:
  - list item: `- **rule-<n>-BR-<i>** (<Kind>) <text>`; indented lines that
    follow are continuation lines
  - decision-table row: `| **rule-<n>-BR-<i>** | <cell>… |`; no continuation lines
  - `(<Kind>)` is the recommended annotation; its absence is not an error yet
- An acceptance criterion starts `- **rule-<n>-AC-<i>.<k>** (rule-<n>-BR-<i>)`;
  the attribution in parentheses is required (missing = unattributable parse
  error). Given / When / Then each take a continuation line.
- Only declarations prefixed with **this document's id** belong to this
  document; a whole-line reference to another document's item is not a
  declaration and is not diagnosed.
- In prose, item ids are always in backticks; **a bold id is the declaration
  form only** — a line that starts with a bold item id and does not fit the
  forms above is a parse error.

## Relations

- `informs` — the `spec` / `design` / `plan` docs these rules are input for.

## Exclude

- system behaviour: idempotency, retries, timeouts (use the consuming `spec`)
- where and when a rule is checked, and by which component (use `design/`)
- technical design (use `design/`)
- lessons learned and pitfalls
- task breakdown

## Note

Every rule must be decidable. "appropriately", "where necessary" — not finished.

The test against a system requirement: remove the software. If it still holds,
it is a rule.

## Sizing and Splitting

One rule doc is one policy area, and one table answers one question. Review
triggers, not hard gates — when one fires, decide deliberately instead of
appending by default:

- a table can no longer be read in one pass (as an order of magnitude: past
  ~6 input columns or ~15 rows)
- rows keep multiplying because two independent questions are answered in one
  table

How to split a table: name the intermediate value — define it with a
Definition rule (or its own small table), add it to Terms, and let the
downstream table take it as an input. Chains of small tables through named
intermediate values, not one wide table (DMN's decision-requirements pattern,
in text). Restructuring an `active` rule doc this way is a substantive
revision — it goes through the revision round.

When the doc itself has grown into two policy areas, split the doc per
`docs/README.md` (`supersedes` + `archived`), like an oversized spec.
