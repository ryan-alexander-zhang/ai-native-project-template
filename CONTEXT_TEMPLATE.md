# CONTEXT.md Format

## Structure

```md
# {Context Name}

{One or two sentence description of what this context is and why it exists.}

## Language

**Order**:
{A one or two sentence description of the term}
_Avoid_: Purchase, transaction

**Invoice**:
A request for payment sent to a customer after delivery.
_Avoid_: Bill, payment request

**Customer**:
A person or organization that places orders.
_Avoid_: Client, buyer, account
```

## Rules

- **Be opinionated.** One word per concept; the others are aliases to avoid.
- **Flag conflicts.** An ambiguous term goes under "Flagged ambiguities" with its resolution.
- **Tight definitions.** One or two sentences; what it IS, not what it does.
- **Show relationships.** Bold term names; cardinality where obvious.
- **Context-specific terms only.** General programming concepts (timeouts, error types, utility patterns) do not belong, however often used.
- **Group under subheadings** when clusters emerge; a flat list otherwise.
- **Example dialogue.** A dev and a domain expert, showing how the terms interact and where related concepts part.

## Single vs multi-context repos

**Single context (most repos):** one `CONTEXT.md` at the repo root.

**Multiple contexts:** `CONTEXT-MAP.md` at the root lists the contexts, their locations, their relations:

```md
# Context Map

## Contexts

- [Ordering](./src/ordering/CONTEXT.md) — receives and tracks customer orders
- [Billing](./src/billing/CONTEXT.md) — generates invoices and processes payments
- [Fulfillment](./src/fulfillment/CONTEXT.md) — manages warehouse picking and shipping

## Relationships

- **Ordering → Fulfillment**: Ordering emits `OrderPlaced` events; Fulfillment consumes them to start picking
- **Fulfillment → Billing**: Fulfillment emits `ShipmentDispatched` events; Billing consumes them to generate invoices
- **Ordering ↔ Billing**: Shared types for `CustomerId` and `Money`
```

`CONTEXT-MAP.md` exists: read it to find contexts. Only a root `CONTEXT.md`:
single context. Neither: create a root `CONTEXT.md` when the first term is
resolved. With several contexts, infer the one the topic belongs to; if unclear,
ask.
