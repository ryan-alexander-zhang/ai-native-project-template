# Acceptance

## Purpose

How to derive the acceptance criteria (GWT) a `spec` requirement or a `rule`
owes — which examples, how many, how written — and when the set is complete.

This file says **what must be shown**; [TESTING.md](TESTING.md) says at which
level and coverage. Acceptance never names a test level or framework; testing
never derives a GWT. Quality requirements are not derived here: scenarios,
methods, stages come from [QUALITY.md](QUALITY.md). An FR whose acceptance wants
a number that is not a boundary of its own behaviour cites a `QS`.

Form: Given-When-Then (North, 2006) as Specification by Example (Adzic, 2011);
the collaborative version, Example Mapping (Wynne, 2015), is the `AGENTS.md`
pre-review audit.

## Acceptance Pattern

- One behaviour per criterion; an `And` in the `When` usually means two.
- `Given` = the precondition, not the steps that reached it.
- `Then` = one observable outcome, not an implementation detail.
- Declarative: "an invoice is overdue", not a click path.
- Examples **sample** the input space, never enumerate it.
- A criterion restating its requirement verifies nothing.

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

State machine: 0-switch coverage (every transition once) is the floor; 1-switch
(every consecutive pair) where an out-of-order transition is costly. Inputs
combining past one table: sample pairwise.

## Omission Heuristics

The tables cannot find a case nobody wrote down. Sweep before closing:

- **cardinality** — zero, one, many
- **timing** — too early, too late, timeout, out of order, duplicate delivery, concurrent
- **lifecycle** — acting after delete, re-entering a terminal state, replay
- **authority** — unauthorised actor, cross-tenant access
- **quantity** — negative, zero, precision, unit or currency, rounding direction

Fill in the omissions this domain actually produces:

- `<recurring miss>` — `<where it bites>`

## Definition of Done

- every `FR` and `BR` in scope carries at least the minimum set for its kind or
  EARS type
- the omission heuristics were swept; what they raised is a criterion or an
  Open Question
- no criterion restates its requirement
- each criterion names a single observable outcome
