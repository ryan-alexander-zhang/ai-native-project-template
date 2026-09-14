---
name: autopilot
description: Unattended run from a one-line idea prompt to a review-ready PR — idea → prd → quality → architecture → spec/rule/design → plan → code → acceptance record. Use when the user invokes /autopilot <idea prompt>. One intake round, then no questions; every open question becomes a decision doc for later human review.
---

# Autopilot

The rules live in `AUTOPILOT.md` at the repo root. Read it, then run it.

- `/autopilot resume <slug>` continues the run whose ledger is `.autopilot/<slug>.md`.
- Any other prompt starts a new run at Intake.

## Intake questions

Ask only what the prompt does not already answer, in one round.

- Scope: what must this deliver, and what is explicitly out?
- Constraints: language, storage, framework, deployment target, integrations?
- Profile (`QUALITY.md`, Profile — orders of magnitude, not targets): how many
  users and how often; peak traffic and its shape; data volume, growth, and how
  long this version must hold; read-heavy or write-heavy; what a user must see
  right after acting (consistency) and what is worse — wrong or unavailable;
  what an hour of downtime and an hour of data loss cost; who waits, and how
  long (latency class); whose data and which regulation; single or
  multi-tenant; who can reach it; who runs it and when; maintenance windows or
  zero-downtime; budget posture. Anything unanswered takes the smallest value
  and is listed as a decision.
- Quality: which quality attributes matter most, in order, and what targets do
  you already hold — a number, a percentile, a window? What environment can the
  run measure in?
- Done: what would you check first to call this finished?
- Reserved: is there anything you want to decide yourself rather than leave to me?
