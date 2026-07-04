---
id: spec-00001-example-slug
type: spec
role: main|patch
status: draft|active|archived
parent: <prd-id | idea-id | empty = spec is the entry point>
---

# Spec: <Feature name>

> One sentence: the coherent, shippable capability this spec delivers.
> A spec is one FEATURE. It orchestrates 1..N user stories (each its own doc),
> the cross-cutting requirements, and the technical design.

## 1. Context
- Canonical terms from `CONTEXT.md`; note any term this spec adds or narrows.
- Inputs: `parent` above (prd/idea), related `analysis` / `reference`.

## 2. User Stories
Each story is its own `docs/us/` doc that owns its value statement, EARS
requirements, and GWT acceptance. Reference a requirement globally as
`us-<n>-FR-<i>`.

| Story | Doc | Status | Summary |
| --- | --- | --- | --- |
| US1 | [us-00001-pay-invoice](../us/us-00001-pay-invoice.md) | active | Creator pays an unpaid invoice by card |
| US2 | [us-00002-payment-outcome](../us/us-00002-payment-outcome.md) | draft | Creator sees the payment outcome and reason |

> Small-spec exception: one small story may be written inline here instead of a
> separate file. Split into files once there are multiple stories, or a story is
> reused / tracked on its own.

## 3. Cross-cutting / System Requirements
Requirements that support the feature but belong to no single story
(idempotency, reconciliation, security). Numbered with this spec's id:
`spec-<n>-XFR-<i>` (XFR/XAC mark spec-level cross-cutting, vs a story's FR/AC).
- **spec-00001-XFR-1** (Unwanted) If the same webhook is delivered more than once, the system shall apply it at most once.
- **spec-00001-XFR-2** (Unwanted) If the provider does not respond before timeout, the system shall keep the attempt PROCESSING and reconcile it later.

**Acceptance (GWT)**
- **spec-00001-XAC-1.1** (spec-00001-XFR-1)
  Given a webhook already processed
  When the same webhook is delivered again
  Then the system makes no further state change

## 4. Technical Design
Default: extract to a `docs/design/` doc and link it here. The design may be
written first and associated with this spec later, and one design may be linked
by more than one spec.
Small-spec exception: keep the design inline in this section when the scope is
small; extract to a file once it is reused or needs independent review.
> Linked design: [design-00001-<slug>](../design/design-00001-<slug>.md)

### 4.1 API
- `POST /invoices/{invoiceId}/payment-attempts` — create a payment attempt

### 4.2 State
`UNPAID → PROCESSING → PAID` · `UNPAID → PROCESSING → FAILED → UNPAID`

### 4.3 Data
- `invoice` · `payment_attempt` · `provider_webhook_event`

### 4.4 Error Handling  (each row maps to a requirement id)
| Error | Handling | Requirement |
| --- | --- | --- |
| card_declined | keep UNPAID, show reason | us-00001-FR-4 |
| provider_timeout | stays PROCESSING, reconcile | spec-00001-XFR-2 |
| duplicate_webhook | idempotent no-op | spec-00001-XFR-1 / spec-00001-XAC-1.1 |
| idempotency_conflict | reject with conflict | spec-00001-XFR-1 |

## 5. Out of Scope (optional)
- …

## 6. Non-Functional (optional)
- performance / security / observability constraints

## Links
- Design: <design-id (default) or "inline" for a small spec>
- Plan: <plan-id> · Issue: <issue-id> · Analysis: <analysis-id>
