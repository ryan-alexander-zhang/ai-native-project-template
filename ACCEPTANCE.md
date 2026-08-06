# Acceptance

## Purpose

This file defines how to derive the acceptance criteria (GWT) a `spec`
requirement or a business `rule` owes, and how to tell when the set is complete.

Use it to decide:
- which examples a requirement or a rule owes
- how many are enough
- how to write each one

Scope boundary: this file says **what must be shown**. [TESTING.md](TESTING.md)
says at which level it runs and what coverage it must reach. Acceptance never
names a test level or a framework; testing never derives a GWT.

The form is Given-When-Then (North, 2006) used as Specification by Example
(Adzic, 2011); the collaborative version is Example Mapping (Wynne, 2015),
which `AGENTS.md` already carries as the pre-review audit.

## Acceptance Pattern

- One behaviour per criterion — an `And` in the `When` usually means two criteria.
- `Given` states the precondition, not the steps that reached it.
- `Then` states one observable outcome, not an implementation detail.
- Write declaratively: "an invoice is overdue", not a click path.
- Examples **sample** the input space; they do not enumerate it.
- A criterion that restates its requirement in other words verifies nothing.

## Minimum Set — by rule kind

| Kind | Minimum set | Technique |
| --- | --- | --- |
| Definition | one typical derivation; one per segment when the derivation is piecewise | equivalence partitioning |
| Constraint | one case that satisfies it, and one that violates it and shows the violation response | — |
| Decision, hit policy `UNIQUE` | one per row, plus one input that matches no row — pinning what happens when the table does not decide | decision table testing |
| Decision, hit policy `FIRST` | one per row, plus both sides of every boundary an earlier row creates | boundary value analysis |

## Minimum Set — by EARS type

| Type | Clause | Minimum set |
| --- | --- | --- |
| Ubiquitous | *(no condition)* | one typical case; one at the edge of the invariant |
| Event-driven | `When <trigger>` | the trigger in a state that accepts it; the trigger in a state that does not |
| State-driven | `While <state>` | the behaviour inside the state; entry; exit |
| Optional feature | `Where <feature is included>` | feature present; feature absent |
| Unwanted | `If <trigger>, then` | the failure and its defined response; a second occurrence when that response must be idempotent |
| Complex | combined clauses | decompose into the types above, then apply each |

For a state machine, 0-switch coverage (every transition once) is the floor; add
1-switch (every pair of consecutive transitions) where an out-of-order
transition is costly. When inputs combine past what one table can express,
sample pairwise rather than enumerating.

## Omission Heuristics

The tables above complete the set against what is written. They cannot find a
case nobody wrote down. Sweep these before closing the set:

- **cardinality** — zero, one, many
- **timing** — too early, too late, timeout, out of order, duplicate delivery, concurrent
- **lifecycle** — acting after delete, re-entering a terminal state, replay
- **authority** — unauthorised actor, cross-tenant access
- **quantity** — negative, zero, precision, unit or currency, rounding direction

Fill in the omissions this domain actually produces:

- `<recurring miss>` — `<where it bites>`

## Definition of Done

An acceptance set is done when:

- every `FR` and every `BR` in scope carries at least the minimum set for its
  kind or EARS type
- the omission heuristics were swept, and what they raised became a criterion or
  an Open Question
- no criterion restates its requirement
- each criterion names a single observable outcome
