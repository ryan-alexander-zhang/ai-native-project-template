---
id: operation-00001-example-slug
type: operation
status: draft|active|archived
implements: [<id>, ...]                       # the decision or design this procedure carries out, or the quality scenarios (QS ids) whose runtime verification this doc holds
---

# Operation: <the procedure>

> One sentence: when someone reaches for this document.

## 1. Prerequisites

- <access, tooling, and state the operator needs before starting>

## 2. Procedure

1. <step — the exact command, and what a good result looks like>
2. <step>

## 3. Verification and Rollback

- Verify: <the signal that says it worked>
- Rollback: <how to undo it, and the point of no return>

## 4. Troubleshooting

| Symptom | Cause | Action |
| --- | --- | --- |
| <what the operator sees> | <why> | <what to do> |

## 5. On-call and Release Notes (optional)

- <escalation path, schedule, or release cadence this procedure sits in>

## 6. SLOs (when this doc implements runtime scenarios)

| QS | SLI (query) | SLO (= the scenario's Measure) | Window | Alert | Error budget policy |
| --- | --- | --- | --- | --- | --- |
| <quality-00001-QS-1.2> | <the metric and how it is computed> | <99.5 % ≤ 300 ms> | <28 d rolling> | <rule name, burn-rate thresholds, who is paged> | <what stops when the budget is spent, who decides resumption> |
