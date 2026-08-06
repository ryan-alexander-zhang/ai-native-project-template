---
id: decision-00008-drop-the-memory-type
type: decision
status: draft
---

# Drop the `memory` type

## Context

`memory/` was a **core** folder for "reusable long-term knowledge", with a Must
Include list of: domain knowledge, glossary, business rules, common patterns,
agent constraints.

Every item on that list has a better-defined home, and most of them are already
claimed by name elsewhere:

| Claimed by `memory` | Actually owned by |
| --- | --- |
| glossary | `CONTEXT.md` — `AGENTS.md` §5 names it the project glossary and requires conflicts be resolved there |
| business rules | `rule/` — decision-00007 |
| domain knowledge | `rule/` when it is policy, `analysis/` when it is a finding |
| agent constraints | `AGENTS.md` and the root canonical docs — `DOCUMENT.md` already says repo-wide policy and workflow stay out of `docs/` |
| common patterns | `design/`, or the root canonical docs |

So the folder did not hold a category; it held the residue of five other
categories. That is not a harmless overlap — a type whose scope is "whatever did
not fit" attracts the things that *did* have a home but were filed in a hurry.
It demonstrably misled: the "business rules" line sat in its Must Include and was
the reason business rules were, for a while, recommended into a folder meant for
recurring pitfalls.

Two structural facts confirm it never worked as a type:

- `memory/TEMPLATE.md` declared **no relation fields at all** — not even
  `informs`. It was the only living type that could neither cite another document
  nor be cited into the graph. A document that cannot be referenced cannot be a
  source of truth for anything.
- After the docs system was built out on `main`, `docs/memory/` still contained
  only its `README.md` and `TEMPLATE.md`. No memory document was ever written.

## Decision

Retire the `memory` type. Delete `docs/memory/`.

Durable guidance about *how to work* — recurring pitfalls, working conventions,
agent constraints — is not a project document. It belongs in the root canonical
docs (`AGENTS.md` and the files it points to), which `DOCUMENT.md` already
designates for repo-wide policy and workflow. `docs/` holds documents *about the
product and the system*, not about the team's habits.

## Options considered

- **Keep `memory` and give it relation fields.** Rejected: it fixes the graph
  isolation but not the overlap. The folder would still be defined as the union
  of five categories that each have a sharper owner, so it would keep collecting
  misfiled documents — the failure mode that actually occurred.
- **Narrow `memory` to recurring pitfalls only**, dropping glossary, rules,
  domain knowledge and agent constraints from its scope. This is the real
  alternative and the one that survives the overlap argument. Rejected on the
  structural ground that `DOCUMENT.md` puts working guidance outside `docs/`, so
  a pitfall log under `docs/` contradicts an existing rule. **Whether that rule
  is the right one — i.e. whether this project wants an in-repo, reviewable
  pitfall log at all — is not answerable from the repo. See "Needs input".**
- **Fold it into `record/`.** Rejected: `record` is time-based and
  evidence-based, a point-in-time artifact of one verification or retrospective.
  Durable reusable knowledge is its opposite, and mixing them would make
  `record` un-prunable.

## Consequences

- `docs/memory/` — deleted (`README.md`, `TEMPLATE.md`; no instance documents
  existed).
- `docs/README.md` — `memory` removed from the type enum, the living-doc list and
  the folder list; the "Keep long-term knowledge in `memory/`" rule is replaced by
  a line routing how-to-work guidance to the root canonical docs.
- `docs/rule/README.md` — the Exclude entry for pitfalls no longer redirects to
  `memory/`; it states the boundary directly (a rule is policy, not experience).
- `docs/decision/decision-00007-separate-rules-requirements-stories.md` — still
  `draft`; its Context and Options sections are annotated to point here, and its
  Consequences no longer promise an edit to a deleted file.
- `docs/decision/decision-00001-doc-status-lifecycle-by-kind.md` — its inline
  living-doc list had already gone stale twice (`us`, now `memory`). Replaced with
  the closed work-item set plus "every other type", and a pointer to
  `docs/README.md` as the live list. The decision itself is unchanged.
- `docs/decision/decision-00004-relations-are-explicit-fields.md` — same drift,
  same fix: the two inline type enumerations are replaced by the rule plus a
  pointer to the templates, which that decision already argues are the real
  guard.
- Trade-off accepted: a project that wants an in-repo pitfall log now has to put
  it in the root canonical docs, where it is not versioned as a document with a
  status and cannot be cited by id.

## Needs input before promotion to `active`

1. Confirm the premise: recurring-pitfall knowledge lives outside this repo
   (agent memory, `CLAUDE.md`) rather than in it. This decision is written on
   that assumption; if it is wrong, the "narrow `memory` to pitfalls only" option
   above is the better choice and needs a real rejection reason instead of a
   structural one.
2. `docs/README.md` now routes that content to "the root canonical docs". Should
   it name a specific file, or stay deliberately unspecified?
