---
id: us-00001-guest-checkout-without-account-creation
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
- [ ] AC-01: Given a shopper has items in the cart and is not signed in, when they open checkout, then they can continue as a guest without being forced to create an account before placing the order.
- [ ] AC-02: Given a guest shopper provides the required checkout information, when they submit a valid order, then the system places the order successfully without creating a customer account.
- [ ] AC-03: Given a guest checkout order is placed successfully, when checkout completes, then the shopper sees an order confirmation that uses the contact details they entered during checkout.

# Definition of Done
- [ ] The checkout flow supports guest order placement from cart through confirmation.
- [ ] Product or UX copy makes account creation optional instead of required during checkout.
- [ ] Test coverage confirms shoppers can place an order without signing in or creating an account.
