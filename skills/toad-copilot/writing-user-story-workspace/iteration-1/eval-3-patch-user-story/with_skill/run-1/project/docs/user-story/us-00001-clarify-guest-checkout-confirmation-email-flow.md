---
id: us-00001-clarify-guest-checkout-confirmation-email-flow
type: us
role: patch
status: draft
parent: prd-00020-checkout-redesign
function_requirement_id: FR-03
---

# User Story

As a guest shopper,
I want a confirmation email sent to the address I entered as soon as my checkout succeeds,
So that I can verify my order was received without creating an account.

# Acceptance Criteria  
- [ ] AC-01: Given a guest shopper completes checkout successfully, when the order is created, then the system sends one confirmation email immediately to the email address entered during checkout.  
- [ ] AC-02: Given a successful guest checkout is routed to manual review, when the confirmation email is sent, then it states the order was received and is pending review instead of implying payment capture or fulfillment is complete.  
- [ ] AC-03: Given a guest shopper completes checkout successfully, when the confirmation email is sent, then it includes the order number and makes clear the shopper can use the email as their order record without creating an account.

# Definition of Done  
- [ ] Implemented  
- [ ] Documented
- [ ] Tested
