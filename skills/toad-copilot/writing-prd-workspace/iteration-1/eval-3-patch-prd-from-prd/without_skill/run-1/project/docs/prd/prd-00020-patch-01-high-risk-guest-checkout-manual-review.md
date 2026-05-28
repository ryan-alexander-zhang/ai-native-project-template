---
id: prd-00020-patch-01-high-risk-guest-checkout-manual-review
type: prd
role: patch
status: active
parent: prd-00020-checkout-redesign
---

# One-line Summary

Define the manual review workflow for high-risk guest checkout orders covered by FR-02 in `prd-00020-checkout-redesign`.

# Vision & Goals

Keep guest checkout fast for low-risk shoppers while giving operations a clear, auditable process for reviewing risky orders before capture and fulfillment.

# Actor

Operations analysts and guest shoppers with flagged orders.

# In Scope

Manual review queue entry criteria, analyst decision workflow, hold behavior before capture, and post-decision customer communication for high-risk guest checkout orders.

# Out of Scope

Changing fraud scoring models, rebuilding the operations console, and adding automated appeals for rejected orders.

# Functional Requirements

- [ ] FR-02A: Mark a guest checkout order as requiring manual review when checkout risk signals exceed the approved threshold.
- [ ] FR-02B: Hold payment capture and fulfillment release while a flagged guest checkout order is pending manual review.
- [ ] FR-02C: Show analysts the order summary, shopper contact details, payment authorization state, and the risk reasons that triggered review.
- [ ] FR-02D: Let an analyst approve or reject a flagged order with a required decision reason and an audit timestamp.
- [ ] FR-02E: Resume the normal confirmation flow after approval and send a rejection notification after rejection.
- [ ] FR-02F: Alert operations when a flagged order remains in manual review past the review SLA.

# User Experience

Low-risk guest shoppers should continue through checkout without seeing any new friction. Shoppers with flagged orders should receive clear, non-alarming communication while review is pending and a definitive follow-up after the analyst decision.

# Risks

False positives and slow analyst response times could delay legitimate orders and reduce conversion.

# Dependencies

Checkout risk signals, the operations review queue, payment authorization hold support, and transactional email templates for approval and rejection outcomes.
