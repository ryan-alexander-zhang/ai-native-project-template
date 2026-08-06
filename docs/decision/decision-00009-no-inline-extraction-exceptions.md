---
id: decision-00009-no-inline-extraction-exceptions
type: decision
status: draft
supersedes: [decision-00002-spec-links-a-design-doc]
---

# A spec links; it never inlines. No small-doc exceptions

## Context

Three assets are extracted out of a spec: the story (decision-00005, then
decision-00007), the technical design (decision-00002), and the business rule
(decision-00007). Each extraction shipped with a "small X" inline exception —
keep it in the spec while it is small, extract it once it is reused or needs
independent review.

Every one of those exceptions has now been removed, and each removal was forced
by the same failure. decision-00005 recorded it in detail for stories: the
exception was written with a cap of one inline story, a spec inlined four, and
the requirements that should have had story ids were relabelled as cross-cutting
to fit. The exception did not merely permit thin files; it pushed content into
the wrong namespace and out of acceptance.

The mechanism generalises. "Small" is not a property anyone can check at review
time, so the exception is enforced by nothing. The author who is already in the
spec, already holding the context, is exactly the author for whom inlining is
cheapest and extraction feels like ceremony — so the exception is taken by
default, not by exception. And "extract it once it is reused" arrives too late:
by the time a second spec needs the rule or the design, the inline version is
already load-bearing, and the extraction that should have cost one file now costs
a migration.

The spec template made this concrete. Its Technical Design section carried
`5.1 API`, `5.2 State`, `5.3 Data`, `5.4 Error Handling` as filled-in subsections
— design structure sitting inside the spec, inviting exactly the inlining the
default was supposed to discourage. A template that ships the shape of the
exception teaches the exception.

Those four headings were doubly wrong: they are not "the" structure of a design
at all, only the structure of one kind of design — a request/response service.
A domain design, a database design, an integration design each want a different
shape. Hard-coding one kind into the spec template both invited inlining and
narrowed what a design was allowed to be.

## Decision

A spec links its rules and its design. There is no inline form of either, at any
size.

- **`rule`** — every rule set is its own `rule/` doc. `spec-<n>-BR-<i>` is not a
  valid id.
- **`design`** — every design is its own `design/` doc. No design content of any
  kind appears in a spec.
- The spec's Business Rules and Technical Design sections are **link tables and
  nothing else**.
- The spec template's `5.1`–`5.4` subsections are **deleted, not relocated**. A
  design has no fixed structure, so `design/TEMPLATE.md` stays minimal — identity,
  the requirements and rules it answers, links — and `design/README.md` carries
  guidance on which form of expression suits which subject instead of a section
  list.
- Error and rejection behaviour is an **Unwanted** EARS requirement in the spec
  (`If … then …`), which is where it already was; the error-handling table was
  restating it and is dropped rather than moved.

A spec answers *what the system must do*. It holds requirements and their
acceptance, and pointers to everything else.

## Options considered

- **Keep the exceptions but make "small" checkable** — a line cap, a row cap.
  Rejected: decision-00005 already tried a cap ("at most one inline story") and
  it was exceeded without anyone noticing, because a cap written in prose is
  checked by nobody. A cap that a script could enforce would have to count lines,
  which is not what makes something too big to inline.
- **Keep the design exception, drop the rule exception.** The asymmetry has an
  argument: a design is single-feature more often than a rule is. Rejected: the
  failure mode is not about reuse frequency, it is about the exception being the
  path of least resistance at authoring time. Both assets are read by people who
  are not reading that spec — an implementer looking for the shape, a reviewer
  looking for the policy — and both are invisible to them while inlined.
- **Allow inline as a drafting stage, require extraction before `active`.**
  Rejected: it is the current rule with a deadline attached, and the deadline
  lands at the moment of least appetite for churn. It also means a `draft` spec
  and an `active` spec have different shapes, so the template cannot show both.

## Consequences

- `docs/spec/TEMPLATE.md` — §3 Business Rules and §5 Technical Design become link
  tables; `5.1`–`5.4` are deleted; the Links block no longer offers "inline" as a
  value.
- `docs/spec/README.md` — Must Include says "always a link" for both; the two
  exception clauses are gone; Exclude gains "business rules of any size" and
  "implementation shape of any size or kind"; a note states that error behaviour
  is an Unwanted EARS requirement, not a table.
- `docs/rule/README.md` — "When To Extract" becomes "Always Its Own File".
- `docs/design/README.md` — "When To Extract" is deleted; the section existed only
  to explain when to take the exception, so with no exception it has nothing to
  say, and the rule now lives in `spec/README.md` where the choice was being made.
  Two sections are added: **Scope**, stating a design has no fixed structure and
  listing the kinds it covers, and **Expressing the Design**, mapping subject to
  form — class diagram for a domain, ER diagram plus DDL for a database, state
  diagram for a lifecycle, sequence and flowchart for interaction and branching,
  and the contract itself for an API. Must Include is unchanged.
- `docs/design/TEMPLATE.md` — unchanged: front matter only. A TEMPLATE carries
  structure only where the structure is load-bearing — `spec` and `rule` own id
  namespaces with required syntax (EARS/GWT numbering, hit policy, otherwise
  row). Every other type's template is front matter, and a design, having no
  fixed structure, has nothing to prescribe.
- `docs/decision/decision-00002-spec-links-a-design-doc.md` — `archived`. Its
  decision (link by default) survives; only its exception is removed, but the
  exception was half of what it decided.
- `docs/decision/decision-00007-separate-rules-requirements-stories.md` — still
  `draft`; its small-rule exception clause is annotated to point here.
- Trade-off accepted: more thin files. A single-feature design of ten lines still
  gets its own document. decision-00002 avoided this cost and got the exception
  instead; the exception turned out to be more expensive.
- Migration debt: any existing spec with an inline design or inline rules must
  split them. On `lang/java/ddd`, `spec-00002-multi-tenancy` already carried
  migration debt from decision-00005 and decision-00007; this adds its inline
  design to that list.

## Needs input before promotion to `active`

1. The error-handling table is dropped outright, on the reading that its rows
   were restating the spec's Unwanted EARS requirements. Confirm that reading —
   if some projects want the table as a single reviewable surface even at the
   cost of duplication, it should come back somewhere and be named.
