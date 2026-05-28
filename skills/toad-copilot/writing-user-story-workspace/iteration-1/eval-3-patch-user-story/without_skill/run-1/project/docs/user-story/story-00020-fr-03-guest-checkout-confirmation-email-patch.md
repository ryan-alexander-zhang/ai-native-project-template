---
id: story-00020-fr-03-guest-checkout-confirmation-email-patch
type: user-story
role: patch
status: active
parent: prd-00020-checkout-redesign
requirement: FR-03
---

# Summary

Clarify the guest checkout confirmation email flow when manual review is triggered.

# User Story

As a guest shopper, I want the email I receive after checkout to match my order status so that I know whether my order is confirmed or still pending review.

# Acceptance Criteria

- Given a guest checkout order that is approved during checkout, when the order is placed successfully, then the system sends the standard confirmation email immediately.
- Given a guest checkout order that is routed into the manual review queue from `FR-02`, when checkout is submitted successfully, then the system sends an immediate pending-review email instead of the standard confirmation email.
- Given a manually reviewed guest checkout order that is later approved, when the order is cleared to proceed after review, then the system sends the standard confirmation email at that point.
- Given a manually reviewed guest checkout order that is declined, then the system does not send the standard confirmation email.

# Notes

This patch aligns `FR-03` with `FR-02` by separating "order received and pending review" from "order confirmed."
