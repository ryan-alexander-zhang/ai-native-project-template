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

mvn verify        # full gate: unit + Testcontainers integration tests, and the quality gates
                  # (Spotless, PMD/CPD, SpotBugs, JaCoCo + PIT on *-domain). Needs Docker.

# Run the app locally (starts PostgreSQL + Kafka + SigNoz via Docker Compose):
mvn -pl start spring-boot:run     # infrastructure comes up from start/compose.yaml
```

Place an order, then read it back:

```bash
curl -i -X POST localhost:8080/orders -H 'content-type: application/json' \
  -d '{"customerId":"CUST-1","lines":[{"sku":"SKU-1","quantity":2,"unitAmountMinor":100,"currency":"USD"}]}'
curl localhost:8080/orders/<id>
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
   StockReserved ─▶ RequestPayment ─▶ payment authorizes ─▶ ConfirmOrder ─▶ CONFIRMED  │
   StockReservationFailed ─▶ compensate ─▶ CancelOrder ─▶ CANCELLED                     │
   PaymentDeclined ─▶ release stock ─▶ CancelOrder ─▶ CANCELLED                         ◀┘
```

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
| Durable process manager (saga) | `OrderFulfilmentDefinition` (pure decision), `OrderFulfilmentCodecs`, `RuntimeOrderFulfilmentProcess` | `OrderFulfilmentDefinitionTest` (unit), `OrderingFlowTest` (e2e) |
| Ordered compensation (release then cancel) | `OrderFulfilmentDefinition` compensation branches | `PaymentCompensationFlowTest` |
| Business-key idempotency (at-most-once) | `AuthorizePaymentHandler` + `PaymentOperations` port | `AuthorizePaymentIdempotencyTest` |
| Payment authorization rule | `AuthorizationPolicy`, `PaymentDecision` | `AuthorizationPolicyTest`, `PaymentDecisionTest` |
| Web error contract (RFC 9457) | `OrderingProblemCatalog` (composition root) | `ExceptionContractTest` |
| Persistence (MyBatis / PostgreSQL) | `ordering-infrastructure`, `inventory-infrastructure` (`MyBatis*` mappers); schema in `start/.../V1__aggregates.sql` | `OutboxAtomicityTest` |
| Architecture rules (layering, context isolation, event placement) | `AiPersimmonDddRules` applied over `com.example` | `ArchitectureTest`, `PackageInfoTest` |

Try it: `SKU-RESTRICTED` is on the review watchlist (`ManualReviewPolicy`), so an order containing it
is held in `AWAITING_REVIEW` until `POST /orders/{id}/approve-review` clears it — see `ReviewFlowTest`.

## Intentional design decisions worth knowing

- **No public `confirm` endpoint.** Confirming is an *internal* step of the fulfilment saga
  (dispatched only after payment is authorized). Exposing it would let a client bypass the saga's
  preconditions, so `OrderController` offers only `place`, `approve-review`, and `read`. Approving a
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

- **Deadlines / max-lifetime are not wired.** The process manager supports `ScheduleDeadline` and a
  `MaxLifetimeExceeded` backstop, but this scaffold arms neither, so a stuck order waits
  indefinitely. `OrderFulfilmentDefinition.react` guards the backstop input so enabling it cannot
  crash the definition (it rejects cleanly for the runtime to suspend); wiring a real deadline/
  timeout-compensation path is left as an extension.
