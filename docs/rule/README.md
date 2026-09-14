# Business Rules

Business rules. Front matter: `TEMPLATE.md`.

## Must Include

- applicability
- terms the rules are built on
- the rules, each numbered `rule-<n>-BR-<i>` and tagged with its kind
- acceptance, numbered `rule-<n>-AC-<i>.<k>`
- open questions — what is still undecided

Add more when useful.

### Rule kinds

The kind decides what else a rule must state.

| Kind | States | Must also state |
| --- | --- | --- |
| Definition | how a value is derived | — |
| Constraint | what must never be true | the response when it is violated |
| Decision | which outcome applies to which case | a hit policy, and an otherwise row |

A Constraint without a violation response leaves the implementer to invent one.

### Decision tables

1. Hit policy (DMN): `UNIQUE` (exclusive rows, order irrelevant) or `FIRST`
   (ordered, first match wins). Prefer `UNIQUE`: non-overlap is checkable.
2. A `FIRST` table ends with an explicit otherwise row, numbered like any other.
3. `—` = the column does not participate in that row. Never empty or false.

### Condition notation

Rule text and cells are unparsed prose; precision is checked at review:

1. Ranges in interval notation with stated boundaries — `(30, 60]`, `>= 2` —
   never "over 30" or "about a month".
2. A calendar quantity (month, day) states its carry semantics in Terms before
   use ("one month later: same day next month; when absent, last day of that
   month").
3. A quantified condition over a collection states predicate and threshold
   ("count of unpaid invoices `>= 2`"), predicate defined in Terms.
4. A derived number states rounding direction, precision, and where rounding
   applies, in its Definition.
5. A possibly missing input is decided by some row — explicit in `UNIQUE`, the
   otherwise row in `FIRST` — never silent fall-through.

### Acceptance

Every `BR` needs at least one example; an unreferenced rule is unverified.

## Machine-readable form (item grammar)

A line that does not fit is a parse error:

- A rule declaration is a whole line, in one of two forms:
  - list item: `- **rule-<n>-BR-<i>** (<Kind>) <text>`; indented lines that
    follow are continuation lines
  - decision-table row: `| **rule-<n>-BR-<i>** | <cell>… |`; no continuation lines
  - `(<Kind>)` is the recommended annotation; its absence is not an error yet
- An acceptance criterion starts `- **rule-<n>-AC-<i>.<k>** (rule-<n>-BR-<i>)`;
  the attribution in parentheses is required (missing = unattributable parse
  error). Given / When / Then each take a continuation line.
- Only declarations prefixed with **this document's id** belong to it; a
  whole-line reference to another doc's item is not a declaration.
- In prose, item ids are in backticks; **a bold id is the declaration form
  only**.

## Relations

- `informs` — the `spec` / `design` / `plan` docs these rules are input for.

## Exclude

- system behaviour: idempotency, retries, timeouts (the consuming `spec`)
- quality requirements — latency, availability, capacity (`quality/`)
- where, when, and by which component a rule is checked; technical design (`design/`)
- lessons learned and pitfalls
- task breakdown

## Note

Every rule is decidable; "appropriately", "where necessary" = not finished.
Test: remove the software. Still holds → rule.

## Sizing and Splitting

One rule doc = one policy area; one table answers one question. Review
triggers, not gates: a table unreadable in one pass (~6 input columns or ~15
rows); rows multiplying because two questions share one table.

Split a table by naming the intermediate value: a Definition rule (or its own
small table), added to Terms, taken as input by the downstream table — chains
of small tables, not one wide one (DMN decision requirements, in text).
Restructuring an `active` rule doc is a substantive revision. A doc grown into
two policy areas splits per `docs/README.md` (`supersedes` + `archived`).
