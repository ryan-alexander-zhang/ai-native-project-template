---
id: prd-00020-checkout-redesign
type: prd
role: main
status: active
parent: idea-00030-checkout-foundations
---

# One-line Summary

Improve guest checkout completion while preserving enough control for fraud review and order communication.

# Vision & Goals

Reduce checkout friction for first-time shoppers without increasing operational risk.

# Actor

Guest shoppers and operations analysts.

# In Scope

Guest checkout, risk review routing, and confirmation messaging.

# Out of Scope

Payment processor migration, loyalty accounts, and returns flows.

# Functional Requirements

- [ ] FR-01: Let a shopper place an order without creating an account.
- [ ] FR-02: Route high-risk guest checkout orders into a manual review queue before capture.
- [ ] FR-03: Send a confirmation email immediately after a successful guest checkout.

# User Experience

Keep the checkout short, make the review path invisible to low-risk shoppers, and provide clear order confirmation.

# Risks

Manual review latency could reduce conversion if thresholds are too aggressive.

# Dependencies

Payment gateway risk signals and the transactional email service.
