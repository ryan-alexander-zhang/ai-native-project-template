---
name: writing-adr
description: >
  Capture a major decision in `docs/decisions` as the current project's standard decision record.
  Use this whenever the user wants a decision record, architecture decision, business choice
  record, technical trade-off record, stack selection note, or needs to document why one important
  option beat other plausible alternatives. Use it both when the user wants to record a decision
  and when they need help exploring 2-3 viable approaches before choosing, even if they do not
  explicitly say "decision record".
compatibility: Requires python3, a POSIX environment, and write access to the target project's `docs/decisions`.
---

# Writing Decision Records

This skill keeps the historical `writing-adr` name for compatibility, but the repo convention is
now a broader decision-record system under `docs/decisions`.

Use this skill when the user wants to record a significant decision in the current project's
`docs/decisions`.

## What Counts As Decision-Worthy

Write a decision record when the choice:

- changes how the product, workflow, system, or repo is shaped or operated
- is expensive or painful to reverse later
- affects multiple files, components, contributors, or future decisions
- has real alternatives with non-trivial trade-offs
- would make a future maintainer ask "why did we do it this way?"

Good examples include business-shape choices, architecture decisions, stack or tool selection,
operating-model decisions, and accepted or rejected option sets with durable consequences.

Do not use a decision record for routine implementation details, typo fixes, temporary discussion,
or local code decisions that do not change the operating model.

## Defaults

- Use `main` unless the user explicitly asks for a patch.
- Use `draft` unless the user explicitly asks for `active` or `archived`.
- A `main` decision must use a concrete upstream parent from `idea-*`, `prd-*`, or `spec-*`.
- A `patch` decision must use the parent decision id it extends.
- Derive a short slug from the decision title or request.
- Keep the decision record short and concrete.
- Prefer a new main decision plus archiving the old decision when the choice changes materially.

## What A Good Decision Record Must Explain

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
trade-off analysis.

## New Decision Interaction Model

For new decision requests, do not jump straight to drafting, even when the user already has a
preferred answer. The hard part is choosing well, not filling the template.

Before drafting a new decision record:

- inspect related decision records, the parent doc, and nearby code or docs that constrain the decision
- restate the decision in one sentence so the user can confirm the frame
- present three labeled options: `A`, `B`, and `C`
- make `A` the recommended option and explain why it wins in this repo
- keep `B` and `C` credible, not strawmen; one of them can be the status quo or a defer/minimal-change path when that is a real option
- explain the trade-offs of each option in concrete repo terms: complexity, migration cost, operational risk, contributor impact, reversibility, or follow-up work
- ask the user to choose `A`, `B`, or `C`, or to modify the options
- wait for the user's choice before running `create_adr.py` or drafting the body

If the user already proposed a direction, include it as one of the options rather than treating it
as settled automatically.

## Empty Decision History

`python3 scripts/list_adrs.py --json` may legitimately return `[]`. Treat that as a normal
greenfield state, not a blocker.

- Do not keep searching for prior decision records or stall on the empty result.
- Use the parent doc and nearby code or docs as the decision context instead.
- For a new decision, continue directly to framing the choice and presenting `A`, `B`, and `C`.
- Only ask a clarification if a material decision driver is still missing after reading the parent and nearby context.

## Drafting Principles

- Read existing decision records in `docs/decisions` first when the new decision might overlap with prior decisions.
- If no decision records exist yet, say so briefly only when helpful and continue with the parent doc and nearby repo context.
- Read the parent doc when a parent is provided, and scan nearby code or docs if they constrain the decision.
- Use the Nygard structure, but write strong content inside it:
  - `Context` explains the situation, constraints, forces, and the most relevant alternatives.
  - `Decision` names the chosen option unambiguously and explains why it wins here.
  - `Consequences` includes both positive and negative outcomes plus any follow-up work.
- Keep the tone factual and compact. A decision record is not a design essay and not a sales pitch.
- Make trade-offs explicit. A one-sided record that lists only benefits is weak.
- Prefer concrete language over vague claims such as "modern", "scalable", or "best practice".

## Workflow

1. Infer whether the request is to create a new decision, create a patch decision, review an existing decision, list decisions, or archive a decision.
2. If the user wants to inspect current decisions, run:
   `python3 scripts/list_adrs.py [--json]`
   Resolve `scripts/list_adrs.py` relative to this skill directory.
3. If the user wants to archive an existing decision, run:
   `python3 scripts/archive_adr.py "<decision id or file path>" --reason "<short archival reason>" [--json]`
   Then run the validator and reviewer before reporting success.
4. If the user wants a review of an existing decision, read the document and report the gaps in decision quality first: missing alternatives, weak rationale, missing downsides, or unclear consequences. Do not rewrite unless the user asks.
5. For a new decision:
   - require a concrete parent from `idea-*`, `prd-*`, or `spec-*` before drafting; if the parent is missing, ask for it or help find it first
   - inspect related decisions and the parent doc when the decision depends on prior context; if `list_adrs.py` returns `[]`, treat that as "no prior decisions" and continue rather than blocking
   - ask one short clarification only if a material gap blocks credible optioning
   - present `A`, `B`, and `C` with trade-offs, mark `A` as recommended, and ask the user to choose before drafting
   - do not create the file until the user picks an option
6. For a patch decision:
   - inspect the parent decision and the nearby docs or code that motivate the patch
   - if the patch itself has materially different choices, you may use the same optioning pattern
   - otherwise ask a short clarification only when the parent, scope, or trade-off is unclear, then draft directly
7. Once the user has chosen the direction for a new decision, or once a patch decision is clear enough to draft, run:
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
13. If validation fails or the review reports material issues, tighten the record and rerun the checks before reporting success.
14. Report the path back to the user and summarize the core decision in one or two sentences.

## Project Convention

This repo stores document lifecycle in front matter. Keep the body focused on the choice itself,
and use the required sections `Context`, `Decision`, and `Consequences`.
