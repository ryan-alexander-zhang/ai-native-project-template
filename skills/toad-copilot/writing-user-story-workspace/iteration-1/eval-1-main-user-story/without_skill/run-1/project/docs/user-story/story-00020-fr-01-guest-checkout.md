---
id: story-00020-fr-01-guest-checkout
type: user-story
role: main
status: draft
parent: prd-00020-checkout-redesign
requirement: FR-01
---

# Title

Guest shoppers can place an order without creating an account.

# User Story

As a shopper who does not want to create an account,
I want to complete checkout as a guest,
so that I can place my order with as little friction as possible.

# Acceptance Criteria

1. A shopper can start checkout, enter checkout details, provide payment, and place an order without signing in or creating an account.
2. The checkout flow does not block order placement behind account registration, password creation, or email verification.
3. The guest checkout path is clearly available to shoppers who are not signed in.
4. The checkout form only requires the information needed to process and fulfill the order for a guest shopper.
5. When a guest checkout succeeds, the system creates an order record without requiring a customer account to exist first.
6. After a successful guest checkout, the shopper sees an on-screen confirmation that the order was received.

# Scope Notes

- This story covers the ability to complete checkout as a guest.
- Fraud review handling is covered by `FR-02`.
- Confirmation email delivery is covered by `FR-03`.
