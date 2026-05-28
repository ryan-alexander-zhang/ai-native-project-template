---
id: story-00020-fr-02-manual-review-routing
type: user-story
role: child
status: active
parent: prd-00020-checkout-redesign
---

# FR-02: Route High-Risk Guest Checkout Orders to Manual Review

# User Story

As an operations analyst, I want high-risk guest checkout orders routed into manual review before capture so that suspicious orders can be reviewed without slowing the normal guest checkout path.

# Scope

This story covers FR-02 in `prd-00020-checkout-redesign` and relies on payment gateway risk signals to decide whether a guest order should enter manual review.

# Acceptance Criteria

1. Given a guest checkout order is flagged as high risk by the payment gateway, when the shopper submits the order, then the order is added to the manual review queue and payment capture is not completed automatically.
2. Given a guest checkout order is routed to manual review, when an operations analyst opens the queue, then the order is shown as pending review with the risk outcome that triggered the routing.
3. Given a guest checkout order is not flagged as high risk, when the shopper submits the order, then the order continues through the standard checkout flow without entering manual review.
