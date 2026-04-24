---
name: writing-adr
description: >
  Capture a major technical decision in `docs/adrs` as the current project's standard ADR.
  Use this whenever the user wants an ADR, architectural decision record, architecture decision,
  technical trade-off record, or needs to document why one important option beat other plausible
  alternatives. Use it both when the user wants to record a decision and when they need help
  exploring 2-3 viable approaches before choosing, even if they do not explicitly say "ADR".
compatibility: Requires python3, a POSIX environment, and write access to the target project's `docs/adrs`.
---

# Writing ADR

Use this skill when the user wants to record a significant technical decision in the current
project's `docs/adrs`.

## What Counts As ADR-Worthy

Write an ADR when the choice:

- changes how the system is built or operated
- is expensive or painful to reverse later
- affects multiple files, components, teams, or future contributors
- has real alternatives with non-trivial tradeoffs
- would make a future maintainer ask "why did we do it this way?"

Do not use an ADR for routine implementation details, typo fixes, or local code decisions that do
not change the architecture or operating model.

## Defaults

- Use `main` unless the user explicitly asks for a patch.
- Use `draft` unless the user explicitly asks for `active` or `archived`.
- A `main` ADR must use a concrete `prd-*` parent. Do not leave `parent` as `<id>` or use an `idea`, `spec`, or other doc type.
- A `patch` ADR must use the parent ADR id it extends.
- Derive a short slug from the decision title or request.
- Keep the ADR short and concrete.
- Prefer a new main ADR plus archiving the old ADR when the decision changes materially.

## What A Good ADR Must Explain

Before presenting options or drafting, make sure you can answer these questions from the user
request, existing docs, or one short clarification:

- What exactly are we deciding?
- Why now? What changed, broke, or will break if we do nothing?
- What constraints or decision drivers matter here?
- Which options were seriously considered?
- Which option won, and why in this repo rather than in the abstract?
- What downside, cost, or risk are we accepting?
- What follow-up work or review signal does this decision create?

If any of these are missing and the gap is material, ask the user. Do not silently invent the
tradeoff analysis.

## New ADR Interaction Model

For `new ADR` requests, do not jump straight to drafting, even when the user already has a
preferred answer. The hard part of ADR work is usually choosing well, not filling the template.

Before drafting a new ADR:

- inspect related ADRs, the parent PRD, and nearby code or docs that constrain the decision
- restate the decision in one sentence so the user can confirm the frame
- present three labeled options: `A`, `B`, and `C`
- make `A` the recommended option and explain why it wins in this repo
- keep `B` and `C` credible, not strawmen; one of them can be the status quo or a defer/minimal
  change path when that is a real option
- explain the tradeoffs of each option in concrete repo terms: complexity, migration cost,
  operational risk, team impact, reversibility, or follow-up work
- ask the user to choose `A`, `B`, or `C`, or to modify the options
- wait for the user's choice before running `create_adr.py` or drafting the ADR body

If the user already proposed a direction, include it as one of the options rather than treating it
as settled automatically.

## Empty ADR History

`python3 scripts/list_adrs.py --json` may legitimately return `[]`. Treat that as a normal
greenfield state, not a blocker.

- Do not keep searching for prior ADRs or stall on the empty result.
- Use the parent PRD and nearby code or docs as the decision context instead.
- For a new ADR, continue directly to framing the decision and presenting `A`, `B`, and `C`.
- Only ask a clarification if a material decision driver is still missing after reading the PRD and
  nearby context.

## Drafting Principles

- Read existing ADRs in `docs/adrs` first when the new ADR might overlap with prior decisions.
- If no ADRs exist yet, say so briefly only when helpful and continue with the PRD and nearby repo
  context.
- Read the parent doc when a parent is provided, and scan the nearby code or docs if they constrain
  the decision.
- Use the Nygard structure, but write strong content inside it:
  - `Context` explains the situation, constraints, forces, and the most relevant alternatives.
  - `Decision` names the chosen option unambiguously and explains why it wins here.
  - `Consequences` includes both positive and negative outcomes plus any follow-up work.
- Keep the tone factual and compact. An ADR is a decision record, not a design essay and not a
  sales pitch.
- Make tradeoffs explicit. A one-sided ADR that lists only benefits is weak.
- Prefer concrete language over vague claims such as "modern", "scalable", or "best practice".

## Workflow

1. Infer whether the request is to create a new ADR, create a patch ADR, review an existing ADR, list ADRs, or archive an ADR.
2. If the user wants to inspect current ADRs, run:
   `python3 scripts/list_adrs.py [--json]`
   Resolve `scripts/list_adrs.py` relative to this skill directory.
3. If the user wants to archive an existing ADR, run:
   `python3 scripts/archive_adr.py "<adr id or file path>" --reason "<short archival reason>" [--json]`
   Then run the validator and reviewer before reporting success.
4. If the user wants a review of an existing ADR, read the ADR and report the gaps in decision quality first: missing alternatives, weak rationale, missing downsides, or unclear consequences. Do not rewrite unless the user asks.
5. For a new ADR:
   - require a concrete `prd-*` parent before drafting; if the parent PRD is missing, ask for it or help find it first
   - inspect related ADRs and the parent PRD when the decision depends on prior context; if `list_adrs.py` returns `[]`, treat that as "no prior ADRs" and continue rather than blocking
   - ask one short clarification only if a material gap blocks credible optioning
   - present `A`, `B`, and `C` with tradeoffs, mark `A` as recommended, and ask the user to choose before drafting
   - do not create the ADR file until the user picks an option
6. For a patch ADR:
   - inspect the parent ADR and the nearby docs or code that motivate the patch
   - if the patch itself has materially different choices, you may use the same optioning pattern
   - otherwise ask a short clarification only when the parent, scope, or tradeoff is unclear, then draft directly
7. Once the user has chosen the direction for a new ADR, or once a patch ADR is clear enough to draft, run:
   `python3 scripts/create_adr.py "<slug or title>" --json [--role main|patch] [--status draft|active|archived] --parent <id>`
   Resolve `scripts/create_adr.py` relative to this skill directory.
8. Open the new file path returned by the script.
9. Replace the scaffold with real content:
   - set a decision title, not a topic title
   - in `Context`, name the trigger, decision drivers, and options considered
   - in `Decision`, state the chosen option and why it beat the other options
   - in `Consequences`, include both gains and costs
10. Keep the front matter and required sections, but rewrite the placeholder prose freely.
11. Run:
    `python3 scripts/validate_doc.py "<file path>"`
12. Run:
    `python3 scripts/review_adr.py "<file path>"`
13. If validation fails or the review reports material issues, tighten the ADR and rerun the checks before reporting success.
14. Report the path back to the user and summarize the core decision in one or two sentences.

## Project Convention

This repo stores document lifecycle in front matter. Keep the ADR body focused on the decision
itself, and use the required sections `Title`, `Context`, `Decision`, and `Consequences`.
