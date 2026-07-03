---
id: us-00001-example-slug
type: us
role: main|patch
status: draft|active|archived
parent: <spec-id>
---

# User Story: <short name>

As a <role>,
I want <capability>,
so that <value>.

## Requirements (EARS)
Number each requirement with this doc's `us-<n>` id (drop the slug):
`us-00001-FR-<i>`. Write each line as one EARS pattern and tag it:

| Pattern | Template | Use when |
| --- | --- | --- |
| Ubiquitous | The system shall `<response>`. | always active, no trigger |
| Event | **When** `<trigger>`, the system shall `<response>`. | a discrete event drives it |
| State | **While** `<state>`, the system shall `<response>`. | true for a state's duration |
| Optional | **Where** `<feature included>`, the system shall `<response>`. | only when a feature/config is present |
| Unwanted | **If** `<condition>`, **then** the system shall `<response>`. | errors, rejections, edge cases |
| Complex | **While** `<state>`, **when** `<trigger>`, the system shall `<response>`. | combine the above |

- **us-00001-FR-1** (Event) When the Creator opens an unpaid invoice, the system shall display its amount, due date, and payment status.
- **us-00001-FR-2** (Event) When the Creator submits a valid card payment, the system shall create a payment attempt.
- **us-00001-FR-3** (Event) When the provider confirms the payment, the system shall mark the invoice paid.
- **us-00001-FR-4** (Unwanted) If the provider rejects the payment, the system shall keep the invoice unpaid and surface the decline reason.

## Acceptance (GWT)
Number each scenario `us-00001-AC-<i>.<k>`; it verifies `us-00001-FR-<i>`, scenario k.
- **us-00001-AC-1.1** (us-00001-FR-1)
  Given the Creator has an unpaid invoice
  When the Creator opens the invoice page
  Then the system shows the amount, due date, and unpaid status
- **us-00001-AC-2.1** (us-00001-FR-2, us-00001-FR-3)
  Given the Creator has an unpaid invoice
  When the Creator submits a valid card payment
  Then the system creates a payment attempt
  And marks the invoice paid after provider confirmation
- **us-00001-AC-4.1** (us-00001-FR-4)
  Given a submitted card payment
  When the provider rejects it
  Then the invoice stays unpaid
  And the decline reason is shown

## Links
- Spec: <spec-id> · Plan: <plan-id> · Issue: <issue-id>
