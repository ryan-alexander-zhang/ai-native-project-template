---
id: prd-00010-vendor-quote-comparison-copilot
type: prd
role: main
status: active
parent: idea-00010-vendor-ops
---

# Product Requirements Document

## Product

Vendor Quote Comparison Copilot

## Summary

A copilot for operations and procurement teams that ingests vendor quotes, normalizes key commercial and service terms, and presents a structured comparison with recommendation support.

## Problem

Vendor quotes arrive in inconsistent formats, which makes comparison slow and error-prone. Teams spend time manually pulling out pricing, service level details, and contract risks before they can decide which vendor is the best fit.

## Target User

- Operations managers
- Procurement leads

## Goals

- Reduce manual effort required to compare multiple vendor quotes
- Normalize quote details into a single reviewable structure
- Help users understand price, service level, and contract risk tradeoffs quickly
- Provide recommendation support without hiding uncertainty

## Non-Goals

- Full contract redlining or legal review
- Vendor negotiation workflows
- Procurement approval routing or ERP integration
- Automatic vendor selection without user review

## User Workflow

1. A user submits two or more vendor quotes for the same purchasing decision.
2. The copilot extracts and normalizes important fields from each quote.
3. The user reviews a structured side-by-side comparison.
4. The copilot highlights tradeoffs and suggests the strongest option with rationale.
5. The user makes the final decision.

## Functional Requirements

### FR-01 Quote Intake

The product must let a user create a comparison from multiple vendor quotes in a single workspace.

#### Acceptance Notes

- A comparison requires at least two quotes.
- Each quote must retain a link back to its original source input.

### FR-02 Normalized Quote Schema

The product must normalize each quote into a common structure so vendors can be compared consistently.

#### Minimum Normalized Fields

- Vendor name
- Quote date or version identifier when available
- Total quoted price
- Currency
- Pricing cadence or payment structure when available
- Service scope summary
- Service level or SLA details when available
- Contract term length when available
- Renewal terms when available
- Termination terms when available
- Notable exclusions, assumptions, or extra fees when available

### FR-03 Ambiguity Handling

The product must not silently present uncertain or missing extracted values as confirmed facts.

#### Acceptance Notes

- Missing fields must be shown as missing.
- Ambiguous fields must be flagged for review.
- A user must be able to see which parts of the comparison require manual confirmation.

### FR-04 Comparison View

The product must provide a side-by-side comparison across all submitted quotes using the normalized fields.

#### Acceptance Notes

- The comparison must make price, service level, and contract risk easy to review in one place.
- Differences between vendors must be visually identifiable without reading full source documents side by side.

### FR-05 Source Evidence

The product must let the user trace normalized values back to their source quote content.

#### Acceptance Notes

- Each normalized field shown in the comparison must reference the originating quote.
- The user must be able to review source evidence for fields that affect the recommendation.

### FR-06 Recommendation Support

The product must generate a recommendation that explains which quote appears strongest and why.

#### Acceptance Notes

- The recommendation must be advisory, not final.
- The rationale must reference tradeoffs in price, service level, and contract risk.
- If required data is missing or ambiguous, the recommendation must say so.

## Success Criteria

- A user can compare multiple quotes without building a manual spreadsheet first.
- The comparison output is structured enough to support a clear review conversation.
- Recommendation output speeds up decision-making while keeping important uncertainty visible.

## Risks

- Quote formats may vary enough to reduce normalization accuracy in the initial version.
- Recommendation quality will depend on the completeness and clarity of the source quotes.

## Release Scope

### MVP

- Multi-quote intake
- Normalized quote extraction for core comparison fields
- Side-by-side comparison view
- Uncertainty flags for missing or ambiguous data
- Recommendation with rationale and source traceability

### Later

- Deeper contract risk scoring
- Collaboration and approval workflows
- Integrations with procurement systems
