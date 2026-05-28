---
id: decision-00020-checkout-risk-review
type: decision
role: main
status: active
parent: prd-00020-checkout-redesign
---

# Define checkout risk review thresholds

## Context
Guest checkout orders occasionally trigger fraud review. The current heuristics are spread across service code and support runbooks, which makes changes risky and difficult to audit.

## Decision
Record the baseline review thresholds for guest checkout risk scoring in a dedicated decision record so later implementation work can point back to one explicit decision.

## Consequences
- Good: Engineers and operators can find the governing decision in one place.
- Bad: Threshold changes now require updating the decision record when policy changes materially.
- Follow-up: Align the risk scoring service and support runbook with this baseline decision.
