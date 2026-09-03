---
name: autopilot
description: Unattended run from a one-line idea prompt to a review-ready PR — idea → prd → architecture → spec/rule/design → plan → code → acceptance record. Use when the user invokes /autopilot <idea prompt>. One intake round, then no questions; every open question becomes a decision doc for later human review.
---

# Autopilot

The rules live in `AUTOPILOT.md` at the repo root. Read it, then run it.

- `/autopilot resume <slug>` continues the run whose ledger is `.autopilot/<slug>.md`.
- Any other prompt starts a new run at Intake.

## Intake questions

Ask only what the prompt does not already answer, in one round.

- Scope: what must this deliver, and what is explicitly out?
- Constraints: language, storage, framework, deployment target, integrations?
- Done: what would you check first to call this finished?
- Reserved: is there anything you want to decide yourself rather than leave to me?
