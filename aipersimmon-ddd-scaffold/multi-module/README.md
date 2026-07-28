# multi-module — reference DDD project

A worked, end-to-end example built on the AiPersimmon DDD building blocks, and the source from which
the `multi-module` archetype is generated. It exists to **show each building block in a realistic
setting** — not to be a complete product. Three bounded contexts collaborate to fulfil an order:

- **ordering** — the customer-facing context: places orders, owns the order lifecycle, and runs the
  durable fulfilment process manager.
- **inventory** — reserves and releases stock. No HTTP surface; driven by integration events.
- **payment** — authorizes payment. No HTTP surface; owns no persisted aggregate.

Each context is split into the standard layers, one Maven module each:
`*-api` (published cross-context contract) · `*-domain` (model + rules, framework-free) ·
`*-application` (use cases + ports) · `*-infrastructure` (technical port implementations) ·
`*-adapter` (inbound transport). Ordering additionally has `ordering-process-mybatis-plus` (the
durable process manager) and `start` (the Spring Boot composition root + architecture tests).

## Build and run

```bash
# Java 21 is required.
export JAVA_HOME=$(/usr/libexec/java_home -v 21)

# Fast loop, no Docker: the framework-free domain modules and their unit tests (seconds).
# Safe without -am: a *-domain module depends on the library only, never on a sibling.
mvn -pl ordering/ordering-domain,inventory/inventory-domain,payment/payment-domain test

# The acceptance tests. ALWAYS -am: `start` depends on all sixteen sibling modules, and without
# -am Maven resolves them from ~/.m2 instead of building them — so you would be testing whatever
# was last installed there, silently. The symptoms are wildly misleading (an endpoint you just
# wrote appears not to exist). -am costs a few seconds of compilation and is always correct.
mvn -o test -pl start -am

mvn verify        # full gate: unit + Testcontainers integration tests, and the quality gates
                  # (Spotless, PMD/CPD, SpotBugs, JaCoCo + PIT on *-domain). Needs Docker, and
                  # takes a while: containers are Spring beans, so each distinct test context
                  # gets its own PostgreSQL + Kafka pair (roughly a dozen across the module).
                  # That is what lets a test assert against an entire empty database.

# Run the app locally (starts PostgreSQL + Kafka via Docker Compose):
mvn -pl start -am spring-boot:run     # infrastructure comes up from start/compose.yaml

# Optional extras, each behind a compose profile:
docker compose -f start/compose.yaml --profile observability up -d   # SigNoz (traces/metrics/logs)
docker compose -f start/compose.yaml --profile tools         up -d   # kafka-ui on :8080
```

Place an order, then read it back. The app listens on **8090**, and every request carries a
tenant: multi-tenancy is on with `missing-policy=REJECT`, so a header-less call is a 400 before it
reaches the controller. The Flyway demo data (`CUST-1`, `SKU-1`) is seeded under the `__root__`
sentinel, which is why that is the tenant used here — your own tenant needs its own rows.

```bash
curl -i -X POST localhost:8090/orders \
  -H 'content-type: application/json' \
  -H 'X-Tenant-Id: __root__' \
  -d '{"customerId":"CUST-1","lines":[{"sku":"SKU-1","quantity":2,"unitAmountMinor":100,"currency":"USD"}]}'

curl -H 'X-Tenant-Id: __root__' localhost:8090/orders/<id>
```

## The fulfilment flow

```
PlaceOrder ─▶ Order.place ── needs review? ──▶ AWAITING_REVIEW ──(POST /orders/{id}/approve-review)──┐
                    │ no review                                                                       │
                    ▼                                                                                 ▼
             OrderReadyForFulfilmentEvent  ◀──────────────────────────────────────  approveReview clears it
                    │  (domain event ⇒ FulfilmentTrigger)
                    ├─▶ start durable process (AWAITING_STOCK)
                    └─▶ publish OrderReadyForFulfilment integration event  ─▶ inventory reserves stock
                                                                                        │
   StockReserved ─▶ RequestPayment + arm PAYMENT deadline ─▶ payment authorizes ─▶ CONFIRMED
   StockReservationFailed ─▶ compensate ─▶ CancelOrder ─▶ CANCELLED                     │
   PaymentDeclined ─▶ release stock ─▶ CancelOrder ─▶ CANCELLED                         ◀┘
   PAYMENT deadline fires ─▶ same compensation ─▶ CANCELLED
```

The deadline is why "payment never answers" is an outcome rather than a stuck order. It takes the
decline's compensation path unchanged — release the stock, then cancel — because the customer's
position is identical however the payment failed to happen; only the recorded code differs.

Key point: **"placed" and "ready for fulfilment" are distinct facts.** An order held for manual
review reserves nothing and starts no process until it is approved. Only `OrderReadyForFulfilment`
drives inventory and the process manager.

## Component → example → verifying test

| Building block / concept | Where it lives | Verified by |
|---|---|---|
| CQRS command/query buses | `OrderController` → `CommandBus`/`QueryBus`; `PlaceOrderHandler`, `FindOrderHandler` | `OrderControllerValidationTest`, `PlaceOrderBusValidationTest` |
| Aggregate + explicit lifecycle | `ordering-domain/…/order/Order.java`, `OrderStatus` | `OrderPlacementTest`, `OrderLifecycleTransitionsTest`, `ComplexOrderStateChangeDemoTest` |
| Evidence-bearing policy (not a flat table) | `OrderLifecyclePolicy`, `CancellationReason` | `OrderCancellationPolicyTest`, `OrderEvidenceRefTest` |
| Manual-review classification | `ManualReviewPolicy` (decision) vs `Order`/`ReviewRequirement` (lifecycle) | `ManualReviewPolicyTest`, `ReviewFlowTest` (end-to-end) |
| Domain events (subscriber in application layer) | `OrderFulfilmentStarter` on `OrderReadyForFulfilmentEvent` | `ReviewFlowTest`, `OrderingFlowTest` |
| Integration events + transactional outbox → Kafka → inbox | `OrderReadyForFulfilment`, `PaymentRequested` (`*-api`); `PlaceOrderHandler`/`FulfilmentTrigger` publish | `OutboxAtomicityTest`, `IntegrationEventTransportTest` |
| Anti-corruption layers | `StockAvailabilityGateway` (ordering port + infra adapter); `OrderReadyForFulfilmentListener`, `PaymentRequestedListener` (inbound ACLs) | `OrderingFlowTest`, `PaymentCompensationFlowTest` |
| Durable process manager | `OrderFulfilmentDefinition` (pure decision), `OrderFulfilmentCodecs` (a `ProcessSerializationCatalog` for 12 payloads + one hand-written codec where a sealed domain type forbids Jackson annotations), `RuntimeOrderFulfilmentProcess` | `OrderFulfilmentDefinitionTest` (unit), `OrderingFlowTest` (e2e) |
| Ordered compensation (release then cancel) | `OrderFulfilmentDefinition` compensation branches | `PaymentCompensationFlowTest` |
| Deadlines (a wait that can end) | `OrderFulfilmentDefinition` arms/cancels the `PAYMENT` deadline; `OrderFulfilmentInput.PaymentTimedOut` | `OrderFulfilmentDefinitionTest` (unit), `PaymentTimeoutFlowTest` (e2e, payment silent) |
| Cursor-paged read model (no aggregate loaded) | `OrderQueries` + `OrderListMapper` → `GET /orders?customerId=`; `Slice`/`Cursor` | `OrderListPagingTest`, `FindCustomerOrdersHandlerTest` |
| HTTP idempotency (a retry that does not buy twice) | `aipersimmon.ddd.web.idempotency` + `-web-store-jdbc` on `POST /orders` | `OrderIdempotencyTest` |
| Optimistic-lock conflict rendered as 409 | version-checked `save` → `ConcurrencyConflictException` → problem document | `ConcurrentApprovalTest`, `ConcurrentAggregateWriteTest` |
| Dead letters and operator replay | `DeadLetterOpsController` over the `DeadLetters` + `DeadLetterStore` ports (`GET /ops/dead-letters` cursor-paged, `GET /ops/dead-letters/{id}`, `POST /ops/dead-letters/{id}/replay`) | `DeadLetterReplayTest` |
| `Specification` answers, `Invariant` refuses | `CancellableByCustomer` (on `OrderSnapshot.cancellableByCustomer`) vs `OrderLifecyclePolicy`; `POST /orders/{id}/cancel` | `CancellableByCustomerTest`, `SelfCancelTest` |
| Business-key idempotency (at-most-once) | `AuthorizePaymentHandler` + `PaymentOperations` port | `AuthorizePaymentIdempotencyTest` |
| Payment authorization rule | `AuthorizationPolicy`, `PaymentDecision` | `AuthorizationPolicyTest`, `PaymentDecisionTest` |
| Web error contract (RFC 9457) | `OrderingProblemCatalog` (composition root) | `ExceptionContractTest` |
| Persistence (MyBatis / PostgreSQL) | `ordering-infrastructure`, `inventory-infrastructure` (`MyBatis*` mappers); schema in `start/src/main/resources/db/migration/` (`V1` tables → `V2` tenancy → `V3` version → `V4` tenant-scoped keys + indexes) | `OutboxAtomicityTest` |
| Architecture rules (layering, context isolation, event placement) | `AiPersimmonDddRules` applied over `com.example` | `ArchitectureTest`, `PackageInfoTest` |

## What each capability cost to adopt

This project is also the library's first consumer that assembles the components together, so what it
cost to use them is worth recording. "Lines" counts what a consuming project writes, excluding tests
and comments. "Implicit pairing" is the knowledge you had to already have — the part no dependency
list tells you.

| Capability | Lines | New concepts | Implicit pairing | Friction found |
|---|---|---|---|---|
| Versioned aggregate write | ~0 | — | row implements `VersionedRow` **and** carries `@Version`; table needs `version DEFAULT 1` | none — the base class carries it |
| Conflict → 409 | 0 | — | none | the lock 409 is `about:blank` with no `code`, while a *domain* 409 is coded: the one a client should retry is the one it cannot recognise |
| HTTP idempotency | ~8 (yaml) | `Idempotency-Key` | a MyBatis-Plus app still takes a **`-jdbc`** store module; `flyway.components` needs `web-store` | issue-00062, issue-00063 (startup failure), issue-00064 (replay lost `Location`) — all three fixed |
| Cursor paging + read model | ~90 | `Slice`, `Cursor` | the cursor can be the id **only because** ids are UUIDv7 — and a page costs the page **only because** an index covers `(tenant_id, customer_id, id DESC)`; the two are separate, and losing either is silent | issue-00065 (a missing query param was a 500) — fixed; issue-00073 (no index existed; the paging tests passed anyway) — fixed in `V4` |
| Deadlines | ~25 | `DeadlineName`, `ScheduleDeadline`, `CancelDeadline` | arm and cancel on every branch leaving the step; due time must come from `context.now()` | none — this API fits |
| Dead letters + replay | ~45 | `DeadLetters` (read), `DeadLetterStore` (replay) | none | issue-00066: `replay(eventId)` with no way to obtain an `eventId` — fixed by the read port, which deleted this project's hand-written query |
| `Specification` | ~30 | `Specification` | keep one statement of the rule, or the answer and the refusal drift | none |
| Test infrastructure | ~2 | `@ServiceConnection` | — | issue-00067: Kafka — the library's own transport — was the one container the module did not provide; fixed |

Four patterns are worth more than the individual rows:

- **Everything above is cheap to write and easy to get subtly wrong.** The line counts are small;
  the "implicit pairing" column is where the cost actually lives, and no bundle reduces it.
- **Five of the eight turned up a library defect**, all of them invisible to the library's own tests
  — each of its modules is tested alone, so a defect that needs several components assembled, or an
  endpoint with a query parameter, or an operator who does not already know an id, cannot appear
  there. This project is where they appear.
- **The two rows with no friction are the two whose API was designed around the failure mode**
  (deadlines: a timer is an ordinary input; specification: answering is a different job from
  refusing) rather than around the happy path.
- **Fixing the library shortened this project.** Every one of the six defects was fixed upstream, and
  three of those fixes deleted code from *here*: the dead-letter query (a mapper against a table this
  application does not own), the Kafka container declaration and four Testcontainers dependencies, and
  a comment explaining why a replayed `201` pointed nowhere. The ledger's real reading is that each
  friction row was a line of consumer code that should not have had to exist.

## Not demonstrated here, on purpose

| | Why |
|---|---|
| The JDBC stack (`-starter-jdbc`, `-persistence-jdbc`, …) | A second backend would double the build for a story already told. The library's module guide presents it as an equal path ([DOCS.md](DOCS.md)); only this one has a worked example. |
| Redis web stores, rate limiting, replay protection | Idempotency already demonstrates the edge-store wiring; the other two differ only in what they count. |
| `sendAs` / `publishAs` | The replay path preserves identity structurally — the row keeps its id — so nothing here needed the explicit carry-an-existing-identity entry points. They remain unexercised. |
| A second topology (modulith, microservice) | Dropped in `605fab3`; the transport story is the same one, packaged differently. |
| `instance.max-lifetime` | See "Known demo gaps" below. |

Try it: `SKU-RESTRICTED` is on the review watchlist (`ManualReviewPolicy`), so an order containing it
is held in `AWAITING_REVIEW` until `POST /orders/{id}/approve-review` clears it — see `ReviewFlowTest`.

## Intentional design decisions worth knowing

- **No public `confirm` endpoint.** Confirming is an *internal* step of the fulfilment process
  (dispatched only after payment is authorized). Exposing it would let a client bypass the process
  manager's preconditions, so `OrderController` exposes no `confirm` at all. Approving a
  held review *is* a legitimate operator action, and hosts the 404/409 error-contract demos.
- **Payment speaks one word — *authorize*.** This reference demonstrates the authorization step
  only, not a later capture, so `AuthorizePayment`/`AuthorizationPolicy`/`PaymentAuthorized` are used
  end to end (no "charge"/"capture" mixing).
- **Payment owns no persisted aggregate.** Its only technical state is an at-most-once operation-
  dedupe log behind the `PaymentOperations` port, kept in memory in `payment-infrastructure`
  (`InMemoryPaymentOperations`). Even in memory it is an outbound adapter, so it lives in the
  infrastructure layer, not the application layer.
- **Inventory uses a deliberate multi-aggregate transaction.** Reserving mutates several `Stock`
  roots and creates one `Reservation`; the "all lines or none" rule is enforced by the application
  transaction, not a single aggregate (see the note in `ReserveStockHandler`). A `Stock`-per-SKU is
  the natural contention boundary; forcing all SKUs into one aggregate would serialise unrelated
  stock.
- **Reservation is triggered from the "ready" moment, by the application, not by a process effect.**
  `FulfilmentTrigger` publishes `OrderReadyForFulfilment` (which reserves stock) in the same
  transaction that starts the process. This is a hybrid of "event-started process" + "application-
  issued first request" rather than full orchestration where the process issues every command. It is
  intentional and keeps the causal `CommandContext` on the reservation; a fully process-driven first
  step is a reasonable variation.

## Known demo gaps (not defects)

- **The max-lifetime backstop is not armed.** The payment step *is* covered by a real deadline (see
  the flow above), which is the case that matters: it is the only step whose answer comes from
  outside and may never arrive. `instance.max-lifetime` is a blunter, whole-instance cap for flows
  that stall somewhere nobody anticipated, and arming it means deciding what a lifetime-exceeded
  order should do — compensate from an arbitrary step, or suspend for an operator. That is a real
  design choice, not a wiring exercise, so it is left out rather than guessed at.
  `OrderFulfilmentDefinition.react` still guards the `MaxLifetimeExceeded` input so enabling it
  cannot crash the definition: it rejects cleanly and the runtime suspends the instance.
