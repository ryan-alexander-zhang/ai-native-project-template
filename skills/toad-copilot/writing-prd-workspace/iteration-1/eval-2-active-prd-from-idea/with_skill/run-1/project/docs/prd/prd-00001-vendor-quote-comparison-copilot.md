---
id: prd-00001-vendor-quote-comparison-copilot
type: prd
role: main
status: active
parent: idea-00010-vendor-ops
---

# One-line Summary
Vendor quote comparison copilot for operations managers and procurement leads that ingests inconsistent quotes, normalizes key terms, and helps them identify the strongest option faster.

# Vision & Goals
Build a decision-support workflow that removes manual spreadsheet work from vendor quote review. Success means a reviewer can ingest multiple quotes, review a normalized comparison, spot missing or risky terms, and leave with a defensible recommendation or shortlist in one pass. The product should speed up procurement review while keeping the human reviewer in control of the final decision.

# Actor
- Operations managers comparing vendor offers.
- Procurement leads reviewing price, service level, and contract tradeoffs.
- Internal stakeholders consuming a summary of the comparison outcome.

# In Scope
- Ingest multiple vendor quotes from inconsistent source material.
- Extract and normalize comparable quote fields into a structured format.
- Present a side-by-side comparison of price, service levels, contract terms, and exceptions.
- Surface recommendation support and tradeoff summaries for the reviewer.
- Let the reviewer correct extracted values before relying on the comparison output.

# Out of Scope
- Auto-negotiating with vendors or drafting outreach.
- Full contract redlining or legal approval workflows.
- ERP or procurement platform integrations in the first release.
- Automatic purchase approval or vendor selection without human review.
- General-purpose document storage outside the quote comparison workflow.

# Functional Requirements
- [ ] FR-01: A reviewer must be able to create a comparison by adding two or more vendor quotes through upload or pasted quote content.
- [ ] FR-02: The system must extract and normalize each quote into a structured schema that includes vendor name, quoted price, pricing basis, term length, service or SLA commitments, notable exclusions, and comparison notes.
- [ ] FR-03: The system must display normalized quotes in a side-by-side comparison view and clearly mark missing, ambiguous, or non-comparable values.
- [ ] FR-04: The reviewer must be able to review and edit extracted fields before finalizing the comparison, and manual edits must override extracted values in the comparison output.
- [ ] FR-05: The system must generate a recommendation-support summary that highlights the leading option, key tradeoffs, and service or contract risks based on the normalized comparison.
- [ ] FR-06: The system must show review-needed indicators for fields where extraction or normalization is uncertain.

# User Experience
The reviewer starts a comparison, adds vendor quotes, and waits for the copilot to normalize each offer into structured fields. Before relying on the output, the reviewer sees any review-needed fields, corrects values when needed, and then reads a side-by-side comparison plus a short recommendation summary. The interface should prioritize scanability over document viewing, with clear missing-value states and simple callouts that explain why one option looks stronger on price, service level, or contract risk.

# Risks
- Quote formats may vary enough to make normalization unreliable without human cleanup.
- Commercial terms may be described differently across vendors, which makes some comparisons subjective.
- Recommendation quality will fall quickly if extracted fields are incomplete or misclassified.
- Users may over-trust the copilot unless review-needed states are prominent and easy to understand.

# Dependencies
- A quote ingestion and parsing capability that can handle common vendor quote formats.
- A normalized quote schema covering pricing, service levels, contract terms, and exclusions.
- Product agreement on how recommendation support is calculated and explained to users.
- Sample vendor quotes for testing extraction quality and comparison usability.
