---
id: spec-00001-example-slug
type: spec
status: draft|active|archived
parent: <prd-id | idea-id | empty = spec is the entry point>
---

# Spec: <Feature name>

> One sentence: the capability this spec delivers.

## 1. Context
- Canonical terms from `CONTEXT.md`; note any term this spec adds or narrows.
- Inputs: `parent` above (prd/idea), related `analysis` / `reference`.

## 2. Stories

| Story | Value | Delivers |
| --- | --- | --- |
| S1 | As a Creator, I want to pay an unpaid invoice by card, so that I clear my balance without contacting support | spec-00001-FR-1, spec-00001-FR-2 |
| S2 | As a Creator, I want to see why a payment failed, so that I can fix it myself | spec-00001-FR-4 |
| S3 | As a Creator, I want overdue invoices priced predictably, so that I am not surprised by a fee | rule-00001-BR-1 … rule-00001-BR-4 |

## 3. Business Rules

| Rule set | Doc | Covers |
| --- | --- | --- |
| Late fees | [rule-00001-late-fee-schedule](../rule/rule-00001-late-fee-schedule.md) | fee band and grace by days overdue and account tier |

## 4. System Requirements

- **spec-00001-FR-1** (Event) When the Creator submits a valid card payment, the system shall create a payment attempt.
- **spec-00001-FR-2** (Event) When the provider confirms the payment, the system shall mark the invoice paid.
- **spec-00001-FR-3** (Event) When an invoice passes its due date, the system shall assess late fees per `rule-00001-BR-1` … `rule-00001-BR-4`.
- **spec-00001-FR-4** (Unwanted) If the provider rejects the payment, the system shall keep the invoice unpaid and surface the decline reason.
- **spec-00001-FR-5** (Unwanted) If the same webhook is delivered more than once, the system shall apply it at most once.
- **spec-00001-FR-6** (Unwanted) If the provider does not respond before timeout, the system shall keep the attempt PROCESSING and reconcile it later.

**Acceptance (GWT)**

- **spec-00001-AC-5.1** (spec-00001-FR-5)
  Given a webhook already processed
  When the same webhook is delivered again
  Then the system makes no further state change

## 5. Technical Design

| Design | Doc | Covers |
| --- | --- | --- |
| Card payment flow | [design-00001-card-payment-flow](../design/design-00001-card-payment-flow.md) | attempt lifecycle, provider webhooks, reconciliation |

## 6. Out of Scope (optional)
- …

## 7. Non-Functional (optional)
- performance / security / observability constraints

## 8. Open Questions

Delete this section once every question is closed.

- spec-00001-FR-3 — does v1 assess fees on partial payments, or only on the full
  outstanding balance? Needs finance.
- spec-00001-FR-6 — who owns the reconciliation deadline, this service or the
  provider contract? Cite the clause.
- Is the feature in scope for invoices created before the billing migration?

## Links
- Rules: <rule-ids>
- Design: <design-ids>
- Plan: <plan-id> · Issue: <issue-id> · Analysis: <analysis-id>
