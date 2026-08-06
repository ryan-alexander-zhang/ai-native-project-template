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

### Acceptance

1. Every `BR` needs at least one example; an unreferenced rule is unverified.
2. Examples sample the rules, they do not enumerate the input space.
3. A Constraint needs at least one example that violates it.
4. On a `FIRST` table, sample both sides of each boundary an earlier row creates.

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
