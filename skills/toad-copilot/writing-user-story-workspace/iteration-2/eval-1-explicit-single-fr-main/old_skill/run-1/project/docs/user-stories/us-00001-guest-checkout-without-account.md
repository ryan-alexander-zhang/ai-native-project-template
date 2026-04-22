---
id: us-00001-guest-checkout-without-account
type: us
role: main
status: draft
parent: prd-00020-checkout-redesign
function_requirement_id: FR-01
---

# User Story

As a guest shopper,
I want to complete checkout without creating an account,
So that I can place my order with less friction.

# Acceptance Criteria  
- [ ] AC-01: Given a shopper has items in the cart and opens checkout, when they choose to continue as a guest, then they can proceed without being required to sign in or create an account.  
- [ ] AC-02: Given a guest shopper enters the required shipping, billing, and payment information, when they submit checkout, then the order is accepted without asking them to create account credentials.  
- [ ] AC-03: Given a guest shopper successfully places an order, when checkout finishes, then the system shows an order confirmation screen tied to the contact details they provided.

# Definition of Done  
- [ ] The checkout flow allows a guest order from cart through order placement with no mandatory account creation step.  
- [ ] Team documentation explains the guest checkout path and any support-visible limits for guest orders.  
- [ ] Test coverage verifies the guest checkout happy path from checkout entry through confirmed order placement.
