# Visual documentation of this project

Five views of the same code, each answering a different question. They were produced *from* the
source — not from the `README`, and not from each other — so where the `README` and the code disagree,
these follow the code and say so.

| Document | Answers |
|---|---|
| [event-storming.json](event-storming.json) | **What happens, in the business's own words.** The domain as actors, commands, aggregates, policies, constraints, domain events, read models, hotspots and opportunities across the three bounded contexts. Importable into the Event Storming editor. |
| [c4.md](c4.md) | **What runs, and what depends on what.** System context → containers → components (one section per bounded context) → where a code-level zoom is worth it. Plus the integration topology and every cross-cutting concern. |
| [class-diagram.md](class-diagram.md) | **What the types are.** Nine class diagrams: the `Order` aggregate, the cancellation rules, placement and money, the application ports, the process manager, inventory, payment, the persistence adapters, and the cross-context contracts. |
| [sequence-diagram.md](sequence-diagram.md) | **What talks to what, in what order.** Eight flows — happy path, both review answers, reservation failure, payment decline, payment timeout, stock timeout, the self-cancel race, ship-then-refuse — plus the outbox path and payment idempotency in detail. |
| [state-diagram.md](state-diagram.md) | **What states exist, and which transitions are legal.** The four separate state machines: `OrderStatus`, the process manager's `Step`, `ProcessLifecycle`, and the reservation's `released` flag. |

Everything is Mermaid inside Markdown, so it renders on GitHub and in most IDEs with no toolchain.

---

## Boundaries: the rule these documents follow

Every diagram is drawn so that **a node belongs to exactly one bounded context, while an arrow may
cross between them**. That is the discipline `ArchitectureTest` enforces in code
(`BoundedContextRules.dependOnEachOtherOnlyThroughApi`), and the documents mirror it:

- **A reacting policy lives in the reacting context.** `StockReserved` is an *inventory* event, but
  "when stock is reserved, begin fulfilment and ask for payment" is an *ordering* policy. So the event
  node sits in `inv` and the policy node in `ord`, with a `triggers` edge across the boundary.
- **Two `Sku` types are shown, not one.** `ordering.domain.shared.Sku` and
  `inventory.domain.stock.Sku` are separate classes and neither imports the other; the published event
  carries a flat `String`. Collapsing them into one box would misrepresent the isolation.
- **`Reservation` references its order as inventory's own `OrderRef`.** Not ordering's `OrderId` — the
  build would fail if inventory depended on it — but not a bare `String` either: that was the opposite
  mistake, leaving the one id this context's whole compensation path hangs off indistinguishable from
  any other string. A local `@ValueObject` wrapping a non-blank value is the third option.
- **The only shared classes are the `*-api` records**, and they appear in exactly one place:
  [§9 of the class diagrams](class-diagram.md#9-the-cross-context-contracts-api).

## What the Event Storming model deliberately leaves out

The `.json` is a model of the **business** domain, so four things in the codebase are absent on
purpose. Each is covered in `c4.md` instead, which is the right home for them.

| Left out | Why |
|---|---|
| Kafka, PostgreSQL, the outbox relay, the inbox | transport and storage, not business facts. The *facts* they carry are modelled; the pipe is not. |
| The dead-letter ops console (`/ops/dead-letters`) | an operator surface over framework tables this application does not own. Real, and technical. |
| The `payment_operations` dedupe log as a node | it is technical state, which is why it sits behind a port in the *infrastructure* layer even though it stores decisions. Its *rules* are modelled, as the `pay-x-decide-once` and `pay-x-void-settles-race` constraints. |
| Any `externalSystem` node | **there are none.** `CeilingAuthorizationPolicy` decides in-process against a configured ceiling and `RestrictedSkuReviewPolicy` against a configured SKU watchlist, so this system has no external business dependency at all. Both are *ports* now, so an adopter adds the missing system by declaring a bean — but a configurable stand-in is still a stand-in, so both absences remain hotspots rather than invented systems. |

Two modelling choices are worth naming, because a reader could reasonably expect otherwise:

- **The three deadline timers are `domainEvent` nodes in `ord`.** `Stock Reservation Timed Out`,
  `Payment Timed Out` and `Stock Release Timed Out` are ordering's own facts, produced by its deadline
  worker. They are how "the other context said nothing" becomes an outcome instead of a stuck order,
  which makes them business facts, not infrastructure noise.
- **`PaymentRequested` and `StockReleaseRequested` use the `produces` relation, not `emits`.** They are
  published directly by their command handlers with no ordering aggregate changing, so there is no root
  to emit them. The DSL has `command → domainEvent` for exactly this case.
- **The manual-review rule is modelled as a `constraint`, not a `policy`.** It runs *inside*
  `PlaceOrder` before any event exists, and it **routes** rather than refuses — it picks the order's
  initial state. Neither element type fits perfectly; `constraint` is the closer of the two, and the
  node's own description says so. (In code it is a port, `ManualReviewPolicy`, with a configurable
  default — but which Java shape a rule takes is not what an Event Storming element type records.)
- **Only one revision of each event appears.** `OrderReadyForFulfilmentV1` has no node of its own:
  it is the same business fact as `OrderReadyForFulfilment`, and a board that showed both would be
  recording a deployment detail as a domain event. The revision pair is a code concern, documented in
  [class-diagram.md §9](class-diagram.md#9-the-cross-context-contracts-api).

## How to check these documents are still true

```bash
# 1. The Event Storming JSON parses against the DSL v4.0 schema, and every edge relation
#    matches the editor's own connection rules.
#    (The schema lives in the event-storming repo: web/lib/dsl/schema.ts)
#    Import the file via the editor's File menu — a bad model fails safeParse on import.

# 2. Every Mermaid diagram parses.
npx --yes -p mermaid@11 -p jsdom node -e '
  /* see the validate.mjs pattern: jsdom globals + mermaid.parse over each ```mermaid block */'

# 3. The claims about layering, context isolation and event placement are executable.
mvn -o test -pl start -am -Dtest=ArchitectureTest,PackageInfoTest

# 4. The claims about behaviour are executable too — every sequence diagram names its test.
mvn verify        # needs Docker
```

## These documents and the code were changed together

An earlier pass through this project produced a review; five of its findings were then fixed, and both
the code and these documents reflect the result:

| Change | Where to read it |
|---|---|
| The two most business-variable rules became ports with configurable defaults | [class-diagram.md §3, §7](class-diagram.md), `../README.md` → *Replaceable policies* |
| Migrations reorganised one directory per bounded context | [c4.md](c4.md) → level 2, `../start/src/main/resources/db/migration/README.md` |
| `ordering-process-mybatis-plus` renamed to `ordering-process` (it contained no MyBatis-Plus code) | [c4.md](c4.md) → level 3a/3b |
| `OrderSnapshot.status` typed as the enum, so the published schema is derived rather than duplicated | [class-diagram.md §4](class-diagram.md) |
| A worked v1/v2 published-event coexistence example | [class-diagram.md §9](class-diagram.md), `../README.md` → *Published-event schema evolution* |

The `README`'s stale claim that payment's dedupe log is an in-memory `InMemoryPaymentOperations` was
corrected at the same time: it is `MyBatisPaymentOperations` over `payment.payment_operations` — a map
could not be rolled back, so a failed transaction kept the claim and lost the authorization
permanently.
