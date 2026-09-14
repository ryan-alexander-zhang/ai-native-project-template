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
- Profile (`QUALITY.md`, Profile — orders of magnitude, not targets): users and
  frequency; peak traffic and shape; data volume, growth, horizon; read- or
  write-heavy; what a user must see right after acting, and which is worse —
  wrong or unavailable; cost of an hour of downtime and of data loss; who waits
  and how long; whose data, which regulation; single or multi-tenant; who can
  reach it; who runs it and when; maintenance windows or zero-downtime; budget
  posture. Unanswered → smallest value, listed as a decision.
- Quality: attributes that matter most, in order; targets already held (number,
  percentile, window); the environment the run can measure in.
- Done: what would you check first to call this finished?
- Reserved: is there anything you want to decide yourself rather than leave to me?
