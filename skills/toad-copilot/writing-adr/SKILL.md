---
name: writing-adr
description: >
  Capture a major technical decision in `docs/adrs` as the current project's standard ADR.
  Use this whenever the user wants an ADR, architectural decision record, architecture decision,
  technical trade-off record, or needs to document why one important option beat other plausible
  alternatives, even if they do not explicitly say "ADR".
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
- Use `parent: <id>` unless the user gives a specific parent.
- Derive a short slug from the decision title or request.
- Keep the ADR short and concrete.
- Prefer a new main ADR plus archiving the old ADR when the decision changes materially.

## What A Good ADR Must Explain

Before drafting, make sure you can answer these questions from the user request, existing docs, or
one short clarification:

- What exactly are we deciding?
- Why now? What changed, broke, or will break if we do nothing?
- What constraints or decision drivers matter here?
- Which options were seriously considered?
- Which option won, and why in this repo rather than in the abstract?
- What downside, cost, or risk are we accepting?
- What follow-up work or review signal does this decision create?

If any of these are missing and the gap is material, ask the user. Do not silently invent the
tradeoff analysis.

## Drafting Principles

- Read existing ADRs in `docs/adrs` first when the new ADR might overlap with prior decisions.
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
4. For a new or patch ADR, inspect related ADRs and the parent document when the decision depends on prior context.
5. For a new or patch ADR, if the decision, parent, or tradeoff is unclear, ask the user before writing the file.
6. Run:
   `python3 scripts/create_adr.py "<slug or title>" --json [--role main|patch] [--status draft|active|archived] [--parent <id>]`
   Resolve `scripts/create_adr.py` relative to this skill directory.
7. Open the new file path returned by the script.
8. Replace the scaffold with real content:
   - set a decision title, not a topic title
   - in `Context`, name the trigger, decision drivers, and options considered
   - in `Decision`, state the chosen option and why it beat the other options
   - in `Consequences`, include both gains and costs
9. Keep the front matter and required sections, but rewrite the placeholder prose freely.
10. Run:
    `python3 scripts/validate_doc.py "<file path>"`
11. Run:
    `python3 scripts/review_adr.py "<file path>"`
12. If validation fails or the review reports material issues, tighten the ADR and rerun the checks before reporting success.
13. Report the path back to the user and summarize the core decision in one or two sentences.

## Project Convention

This repo stores document lifecycle in front matter. Keep the ADR body focused on the decision
itself, and use the required sections `Title`, `Context`, `Decision`, and `Consequences`.
