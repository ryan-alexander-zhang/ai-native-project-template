---
id: us-00001-high-risk-guest-checkout-manual-review
type: us
role: main
status: active
parent: prd-00020-checkout-redesign
function_requirement_id: FR-02
---

# User Story

As an operations analyst,
I want high-risk guest checkout orders routed to a manual review queue before capture,
So that suspicious orders can be reviewed without adding friction for low-risk guest shoppers.

# Acceptance Criteria  
- [ ] AC-01: Given a guest checkout order is marked high risk by the payment gateway, when the shopper submits the order, then the order is placed in a manual review queue and payment capture does not occur automatically.  
- [ ] AC-02: Given a guest checkout order is not marked high risk, when the shopper submits the order, then the order continues through the normal checkout path without being sent to manual review.  
- [ ] AC-03: Given an order is routed to manual review, when an operations analyst opens the queue, then the order is visible with its guest order details and risk status needed to decide whether to approve or reject it.

# Definition of Done  
- [ ] Implemented with high-risk guest orders held for manual review before capture.  
- [ ] Documented so operations knows which guest orders enter manual review and why.  
- [ ] Tested for both high-risk routing and normal low-risk guest checkout flow.
