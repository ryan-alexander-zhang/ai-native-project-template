---
id: decision-00007-separate-rules-requirements-stories
type: decision
status: draft
supersedes: [decision-00005-every-user-story-is-a-file]
---

# Business rules, system requirements and stories are three different things

## Context

A `us` doc carried three artifacts from three different traditions in one file:

| Content | Tradition | Changes when | Owned by |
| --- | --- | --- | --- |
| `As a… I want… so that…` | Scrum/XP user story | the product bet changes | product |
| `the system shall…` (EARS) | system requirements engineering | the solution shape changes | analyst / architect |
| Given/When/Then | BDD / specification by example | any new edge case is found | QA / developer |

Three abstraction levels, three change rates, three reviewers, one document and
one review gate. Every edge case discovered forced a re-read of the value
statement it had nothing to do with.

Worse, the requirement id namespace was anchored in the most ephemeral of the
three. A user story is a planning token — a card that exists to provoke a
conversation and is then discarded. `us-00001-FR-3` made that card the permanent
target of every downstream trace: plans, tests, acceptance records. An id can
never be retired, so the card can never be discarded, so it grows into a small
requirements specification wearing a story's clothes.

Business rules had no home at all. `memory/README.md` listed "business rules"
under Must Include, but `memory/` held recurring pitfalls and their lessons, and
it carried no relation fields, so it could not enter the document graph. (The
`memory` type has since been retired by decision-00008; at the time of this
decision it was the folder that appeared to claim business rules.)
`design/` fixes the shape of the software, whatever kind of design it is; a rule
holds whether or not that software exists. With neither folder fitting, rules
were flattened into EARS lines inside stories: a
decision table of N rows became N one-sentence requirements that no reader can
reassemble into the table, and whose boundary and fallback behaviour is
unstated.

The literature had settled this long before. The Business Rules Manifesto (Business
Rules Group, 2003) makes a rule a first-class artifact and requires it be expressed
independently of how it is enforced; SBVR layers vocabulary, then facts, then rules
over them; DMN standardises the decision table itself.

Both tools were being used off-level. EARS (Mavin et al., RE'09) was designed for
system requirements, not for the inside of a story. GWT examples illustrate a
rule and cannot define one — Gherkin added a `Rule:` keyword in v6 (2018) for
exactly this gap, and Example Mapping treats Story, Rule, Example and Question as
four distinct card types precisely because collapsing them loses information.

decision-00005 removed the inline-story exception to keep "one id namespace
instead of two". That goal is right and is preserved here; this decision reaches
it from the other side, by moving the namespace off the artifact that should not
have held one.

## Decision

1. **New type `rule`** owns business rules: rows numbered `rule-<n>-BR-<i>`,
   acceptance `rule-<n>-AC-<i>.<k>`. A rule doc is independent and declares
   `informs` on the specs that consume it — the same externalization
   decision-00002 made for `design`, for the same reasons and more strongly: a
   rule set is read by several specs and outlives all of them.
2. **`spec` owns system requirements**: `spec-<n>-FR-<i>`, acceptance
   `spec-<n>-AC-<i>.<k>`. The `XFR`/`XAC` prefix is retired; it existed only to
   avoid a collision with story-held `FR`, and that collision is gone.
3. **A story is a row, not a document.** It names one shippable slice and the
   requirement and rule ids that slice delivers. It owns no id namespace and no
   acceptance of its own. The `us` type is retired.
4. **Boundary test**: remove the software. If the statement is still true it is a
   rule; otherwise it is a system requirement. "A refund is 50% when the goods
   were opened within 24 hours" is a rule. "A duplicate submission returns the
   existing request" is a requirement.
5. **Acceptance follows its subject.** Examples verifying a rule live in the rule
   doc; acceptance for a requirement lives in the spec. Neither duplicates the
   other.
6. **Every rule is tagged with a kind**, on the Business Rules Group's
   definitional/behavioural split: **Definition** (how a value is derived; cannot
   be violated), **Constraint** (what must never be true; must also state the
   violation response), **Decision** (which outcome applies; must declare a hit
   policy and an otherwise row). The kind decides what else the rule must state.
   A Constraint with no violation response leaves the implementer to invent one —
   the same failure as a decision table with no fallback, and the reason a rule
   set modelled only as tables was insufficient.

   Hit policies are DMN's, spelled out rather than using its single-letter cell,
   and only `UNIQUE` and `FIRST` of the seven are adopted.
7. **`rule` and `spec` carry an Open Questions section**, and `DOCUMENT.md` keeps
   a doc with open questions in `draft`. A number nobody can source, or a scope
   call nobody has made, then blocks implementation instead of being guessed.
   `design` has no fixed structure and so no fixed place for the section; an
   unresolved design choice with real trade-offs is a `decision`.

   An open question is not an `issue`: a question is unresolved while the document
   is written and blocks promotion; an issue is a defect found while building
   against a settled document and blocks `resolved`.

## Options considered

- **Keep `us` and only add `rule`.** The conservative option: extract rules, leave
  stories as documents holding EARS and GWT. Rejected on what the repo shows —
  it leaves three requirement namespaces (`us-FR`, `spec-XFR`, `rule-BR`) where
  decision-00005 argued hard for one, and it keeps the trace anchor on the
  artifact with the shortest natural life. **The part that is not in the repo:
  whether anyone actually reads story documents here, and whether product needs
  a per-story artifact independent of the spec. See "Needs input" below.**
- **Put business rules in `design/`.** Rejected: a design fixes the shape of the
  software — a domain model, a schema, an API, a process — and is rewritten
  whenever that shape moves. A business rule holds independently of the software
  and must stay reviewable by someone who cannot read a schema. Filing it with
  the design means re-reviewing the policy every time the implementation
  changes.
- **Put business rules in `memory/`.** Rejected: `memory` held recurring
  pitfalls and reusable lessons, not authoritative policy, and it declared no
  relation fields, so a rule kept there could never be cited into the graph. The
  "business rules" line in its README was a mis-statement. (decision-00008 then
  retired the type outright, so this option no longer exists at all.)
- **Keep rules inline in each spec.** Rejected on decision-00002's grounds: rules
  are reused across specs and outlive them, so ownership by one spec is wrong.
  (This decision originally kept a small-rule inline exception, mirroring the
  small-spec design exception; decision-00009 then removed both.)
- **Move GWT out of the docs into executable `.feature` files**, leaving only id
  references behind. Not decided here. It is the stronger position for an
  AI-native template — a coding agent reads an executable specification more
  reliably than prose, and row coverage becomes a CI check rather than a review
  item — but it makes the docs non-self-contained. Deferred to its own decision.

## Consequences

- `docs/rule/README.md`, `docs/rule/TEMPLATE.md` — new type, with hit policy,
  mandatory otherwise row, and the Open Questions gate.
- `docs/spec/TEMPLATE.md` — Stories become a table of slices with the ids they
  deliver; a Business Rules section links the rule docs; `XFR`/`XAC` become
  `FR`/`AC`; an Open Questions section is added, deleted once empty; section
  numbering shifts.
- `docs/spec/README.md` — new "Stories", "Requirements vs Rules", "Business
  Rules" and "Open Questions" sections; the User Stories section is gone.
- `docs/README.md` — `rule` added to the type enum, the living-doc list and the
  folder list as **core**; `us` removed from all three; the two-namespace rule
  replaces the `us` requirement-unit rule; `parent` now names five types, not
  six. (`reference` was also missing from the type enum and is restored.)
- `docs/us/` — deleted.
- `docs/memory/README.md` — the "business rules" line would have moved to
  Exclude; decision-00008 deletes the folder instead, which settles it.
- `docs/record/README.md`, `docs/record/TEMPLATE.md` — `verifies` and the
  acceptance checklist cite `spec` / `rule` ids; every `BR` row in scope must
  appear in the checklist.
- `AGENTS.md` — the pre-`resolved` verification covers `spec`/`rule` GWT and
  requires no `FR` or `BR` left unverified.
- `DOCUMENT.md` — Status Workflow gains "A doc with open questions stays `draft`".
- `docs/decision/decision-00002-spec-links-a-design-doc.md` — Context annotated:
  its extraction pattern now has two instances, `design` and `rule`. (It was
  subsequently superseded by decision-00009 and archived.)
- `docs/decision/decision-00005-every-user-story-is-a-file.md` — `archived`.
- Trade-off accepted: a spec gets longer, because the requirements that used to
  be scattered across story files now sit in it. That is the point — the feature's
  requirements become readable in one pass — but a large feature will produce a
  large spec, and the guard is the story-slice rule, not the file size.
- Migration debt, inherited and reshaped from decision-00005:
  `spec-00002-multi-tenancy` on `lang/java/ddd` has four inline stories and
  relabelled `XFR` requirements. Under this decision the inline stories become
  table rows and the `XFR` ids become `FR` — mechanical — but the missing
  acceptance is still domain work, and any business rules buried in those
  requirements must be lifted into a `rule` doc. Outstanding on that branch.

## Needs input before promotion to `active`

This doc stays `draft` until these are answered; they are not derivable from the
repo:

1. Does anyone here read a story document, or is the story only ever a planning
   token? If the former, the conservative option above deserves a real rejection
   reason rather than the structural one given.
2. Is `rule` **core** or **situational**? It is written as core on the reasoning
   that a homeless rule gets squeezed back into the wrong folder — but a pure
   library or infrastructure project may have no business rules at all.
3. Should the GWT-to-`.feature` option be opened as its own decision now, or left
   until a real project hits the pain?
4. Two DMN hit policies are adopted, five are not. `C+` (collect and sum every
   matching row) has a real use — stacking discounts, accumulating fees — and a
   two-policy vocabulary cannot express it, so an author will reach for `FIRST`
   and silently drop the other matches. Widen, or keep it narrow deliberately?
