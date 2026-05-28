---
id: us-00001-route-high-risk-guest-orders-to-manual-review
type: us
role: main
status: active
parent: prd-00020-checkout-redesign
function_requirement_id: FR-02
---

# User Story

As an operations analyst,
I want high-risk guest checkout orders routed into a manual review queue before capture,
So that I can review fraud risk without interrupting the normal path for low-risk shoppers.

# Acceptance Criteria  
- [ ] AC-01: Given a guest checkout order is marked high-risk, when the shopper submits the order, then the order is routed into the manual review queue before payment capture.
- [ ] AC-02: Given a guest checkout order is in the manual review queue, when an operations analyst reviews it, then the order remains uncaptured until the review decision is made.
- [ ] AC-03: Given a guest checkout order is not marked high-risk, when the shopper submits the order, then the order does not enter the manual review queue and follows the standard capture flow.

# Definition of Done  
- [ ] Implemented: High-risk guest checkout orders are held for manual review before capture.
- [ ] Documented: The review-queue and capture-hold behavior is documented for operations and support teams.
- [ ] Tested: Automated coverage verifies high-risk orders route to review and low-risk orders bypass review.
