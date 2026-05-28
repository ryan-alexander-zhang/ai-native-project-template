---
id: prd-00021-manual-review-high-risk-guest-checkout-orders
type: prd
role: patch
status: draft
parent: prd-00020-checkout-redesign
---

# One-line Summary
Add a manual review workflow for high-risk guest checkout orders so operations analysts can approve or reject risky purchases without blocking low-risk guest checkout.

# Vision & Goals
This patch adds the operational detail behind `prd-00020-checkout-redesign` for guest orders that are flagged as high risk. The goal is to let the core checkout stay short for most guests while ensuring suspicious orders are held, reviewed quickly, and resolved with a clear decision. Success means flagged orders enter a review state automatically, analysts have enough context to act without leaving the workflow, and shoppers receive accurate status communication.

# Actor
Operations analysts reviewing flagged guest checkout orders, with guest shoppers affected by the review outcome.

# In Scope
- Holding guest checkout orders that are flagged as high risk before capture or fulfillment release.
- Showing reviewers a queue of guest orders awaiting manual review.
- Displaying the core order, contact, payment, shipping, and risk details needed for a disposition.
- Allowing reviewers to approve or cancel a held order and record a reason for the decision.
- Sending guest-facing email messaging that matches the held, approved, or canceled state.

# Out of Scope
- Changing the underlying risk scoring model or threshold logic.
- Adding manual review for signed-in checkout, phone orders, or post-purchase fraud cases.
- Building chargeback tooling, case management, or analyst staffing workflows outside the order review step.

# Functional Requirements
The system must support a manual review path for high-risk guest checkout orders.
- [ ] FR-01: If a guest checkout order is flagged as high risk, the system must place the order into a `pending_review` state before payment capture or fulfillment release.
- [ ] FR-02: The manual review queue must show each pending guest order with order age, order total, guest contact, risk reason or score, and current payment authorization status.
- [ ] FR-03: Reviewers must be able to open a pending guest order and see the order items, billing and shipping details, fraud signals provided by the risk service, and prior review notes for that order.
- [ ] FR-04: Reviewers must be able to approve or cancel a pending guest order, and the system must require a disposition reason for either action.
- [ ] FR-05: Approving a pending guest order must release the order to the normal post-checkout flow and trigger the approved customer notification.
- [ ] FR-06: Canceling a pending guest order must stop capture or fulfillment, mark the order as canceled due to risk review, and trigger the canceled customer notification.

# User Experience
Low-risk guests should experience no extra review friction. For flagged guests, the checkout should complete with a neutral message that the order was received and is being reviewed. Analysts should see a compact queue, open an order detail view with the relevant risk context, and make a decision in one place. Shopper messaging should avoid promising shipment or final approval until review is complete.

# Risks
If too many guest orders are flagged, analysts may build a backlog that delays legitimate purchases. If reviewers do not get enough context in the queue or detail view, they may make inconsistent decisions or escalate work into offline channels. Customer messaging must be precise so the held state does not conflict with the parent PRD's confirmation expectations.

# Dependencies
Risk signal data from the fraud or payment provider, support from the order management system for a review state and dispositions, and transactional email templates for held, approved, and canceled guest orders.
