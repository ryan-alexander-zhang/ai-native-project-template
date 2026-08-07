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
`*-adapter` (inbound transport). Ordering additionally has `ordering-process` (the durable process
manager's policy — storage-agnostic; the store it runs on is chosen in `start`) and `start` (the
Spring Boot composition root + architecture tests).

The library's own documents are not in this tree — see
[Upstream documentation](#upstream-documentation) at the end of this file for where they live.

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
                  # gets its own PostgreSQL + Kafka pair. There are 17 distinct contexts and 16
                  # such pairs (ProductionProfileBootTest reuses a raw container and starts no
                  # broker). That is what lets a test assert against an entire empty database.
                  # TestContextCountTest pins the number, so adding one property to one test
                  # class cannot quietly buy another pair.

# Run the app locally (starts PostgreSQL + Kafka via Docker Compose):
mvn -pl start -am spring-boot:run     # infrastructure comes up from start/compose.yaml

# Optional extras, each behind a compose profile:
docker compose -f start/compose.yaml --profile observability up -d   # SigNoz (traces/metrics/logs)
docker compose -f start/compose.yaml --profile tools         up -d   # kafka-ui on :8080
```

### Configuration profiles

`dev` is the default, so everything above needs no profile argument. The three files divide by one
question — *would this value still be right somewhere else?*

| File | Holds | Examples |
|---|---|---|
| `application.yml` | Decisions. Same value wherever it runs | outbox lease arithmetic, inbox retention, tenancy policy, health probes, graceful shutdown |
| `application-dev.yml` | Local addresses and demo conveniences | compose lifecycle, `localhost:9092`, Swagger UI on, `payment-timeout: PT2M`, the `db/dev` seed location |
| `application-prod.yml` | Everything from the environment | `${DB_URL}`, `${KAFKA_BOOTSTRAP_SERVERS}`, Swagger UI off, `require-key: true`, migrations without `db/dev` |

Production is an explicit opt-in — `SPRING_PROFILES_ACTIVE=prod` — and requires `DB_URL`,
`DB_USER`, `DB_PASSWORD` and `KAFKA_BOOTSTRAP_SERVERS`. Those placeholders carry no defaults on
purpose: a missing one fails at startup rather than silently pointing somewhere wrong.
`application-prod.yml` lists the optional variables and their defaults, and
`ProductionProfileBootTest` starts the application under that profile with nothing but those
variables, so the file cannot rot.

### Before production: the declared debts

Two decisions in this scaffold are demo conveniences that a real deployment **must** revisit, and
both say so where they live (this list exists so they cannot be forgotten):

- **`/ops/**` is unauthenticated and tenant-exempt.** The dead-letter console
  (`DeadLetterOpsController`) is mounted bare because the scaffold ships no security context, and
  `application.yml` exempts `/ops/**` from tenant resolution because an operator acts across
  tenants. A deployment puts it behind an operator role — the exemption is then an authorization
  statement instead of an open door.
- **The in-memory idempotency/nonce/rate-limit fallback stays refused.** The base configuration
  already forbids it (`aipersimmon.ddd.web.allow-in-memory-stores: false` in `application.yml` —
  the JDBC store module supplies the real stores, so the flag only bites if someone removes that
  dependency); keep the refusal when copying the configuration.

Place an order, then read it back. The app listens on **8090**, and every request carries a
tenant: multi-tenancy is on with `missing-policy=REJECT`, so a header-less call is a 400 before it
reaches the controller. Use `demo` — `db/dev/afterMigrate__seed.sql` (dev profile only) seeds
`CUST-1` and the SKUs under it. Your own tenant needs its own rows, and a production database has
none of it.

```bash
curl -i -X POST localhost:8090/orders \
  -H 'content-type: application/json' \
  -H 'X-Tenant-Id: demo' \
  -d '{"customerId":"CUST-1","lines":[{"sku":"SKU-1","quantity":2,"unitAmountMinor":100,"currency":"USD"}]}'

curl -H 'X-Tenant-Id: demo' localhost:8090/orders/<id>
```

> Not `__root__`. That is the sentinel a single-tenant (N=1) deployment stores on every row, and
> `Tenants.of()` rejects the reserved `__` prefix at the edge on purpose — so a client can never name
> a framework sentinel. A curl carrying it is a 400.
>
> It is also not a fallback: with multi-tenancy enabled, work that reaches a tenant-scoped table with
> no tenant bound fails loudly instead of quietly using the sentinel. That is why the
> acceptance tests which dispatch straight on the command bus bind `demo` for the test thread — they
> skip the web edge, so they have to do the edge's job.

## The fulfilment flow

```
PlaceOrder ─▶ Order.place ── needs review? ──▶ AWAITING_REVIEW ──(POST /orders/{id}/approve-review)──┐
                    │ no review                                                                       │
                    ▼                                                                                 ▼
             OrderReadyForFulfilmentEvent  ◀──────────────────────────────────────  approveReview clears it
                    │  (domain event ⇒ FulfilmentTrigger)
                    ├─▶ order saved as READY_FOR_FULFILMENT  (customer may still self-cancel)
                    ├─▶ start durable process (AWAITING_STOCK) + arm STOCK deadline
                    └─▶ publish OrderReadyForFulfilment integration event  ─▶ inventory reserves stock
                                                                                        │
   StockReserved ─▶ BeginFulfilment + RequestPayment + arm PAYMENT deadline ─▶ authorized ─▶ CONFIRMED
   CancelOwnOrder while READY ─▶ CANCELLED; a late StockReserved is released, not fulfilled
   StockReservationFailed ─▶ compensate ─▶ CancelOrder ─▶ CANCELLED                     │
   PaymentDeclined ─▶ release stock ─▶ CancelOrder ─▶ CANCELLED                         ◀┘
   STOCK deadline fires   ─▶ CancelOrder (nothing reserved yet) ─▶ CANCELLED
   PAYMENT deadline fires ─▶ same compensation as a decline     ─▶ CANCELLED
   STOCK_RELEASE deadline fires ─▶ ask for the release again (it cannot give up — see below)

   RejectReview while AWAITING_REVIEW ─▶ CANCELLED (synchronous; nothing was ever reserved)
   ShipOrder while CONFIRMED          ─▶ SHIPPED, and from there cancelling is RETURN_REQUIRED
```

Deadlines are why "nobody answered" is an outcome rather than a stuck order. **Every step that
waits on another context has one.** The earlier claim that payment was the only such step used the
wrong test — "will this context refuse me?" Inventory does answer `StockReservationFailed`, but
only for a *business* failure; a technical one (an optimistic-lock conflict, a validation error, a
database outage) throws out of its handler and publishes nothing, and that silence is
indistinguishable from the payment context's.

The three are not symmetrical, because what a step can do about silence differs. Nothing is
reserved at `AWAITING_STOCK`, so a timeout cancels outright. Stock is held at `AWAITING_PAYMENT`,
so a timeout releases it and then cancels — the decline's path unchanged, because the customer's
position is identical however the payment failed to happen. And a timeout at
`AWAITING_STOCK_RELEASE` **cannot** end the wait: cancelling from there needs a `StockReleaseRef`
proving the stock came back, and a timeout is precisely the absence of that proof. So the flow
re-sends the release request and re-arms the timer — it keeps asking rather than fabricating
evidence. That is the evidence-bearing `CancellationReason` earning its complexity: a looser design
would have "recovered" by declaring released stock that is still held.

Key point: **"placed", "ready for fulfilment" and "fulfilment in progress" are three distinct
facts, and each one is a state a row actually holds.** An order held for manual review reserves
nothing and starts no process until approved. A cleared order is saved as `READY_FOR_FULFILMENT`
and *stays* there while inventory works — asking for a reservation is not having one — so the
customer's self-cancel window is real for every order, not only for the ones that happened to be
held for review. `BeginFulfilment`, dispatched by the process manager when the reservation actually
exists, is what advances it.

That makes the window overlap the reservation, which is a race worth knowing about: the customer can
cancel while inventory is still working. The cancellation wins — it was made while the order was
still theirs to cancel — and the flow releases the stock that arrives for it rather than reviving
the order. `SelfCancelDuringReservationTest` covers it.

## Component → example → verifying test

| Building block / concept | Where it lives | Verified by |
|---|---|---|
| CQRS command/query buses | `OrderController` → `CommandBus`/`QueryBus`; `PlaceOrderHandler`, `FindOrderHandler` | `OrderControllerValidationTest`, `PlaceOrderBusValidationTest` |
| Aggregate + explicit lifecycle | `ordering-domain/…/order/Order.java`, `OrderStatus` | `OrderPlacementTest`, `OrderLifecycleTransitionsTest`, `ComplexOrderStateChangeDemoTest` |
| Evidence-bearing policy (not a flat table) | `OrderLifecyclePolicy`, `CancellationReason` | `OrderCancellationPolicyTest`, `OrderEvidenceRefTest` |
| Manual-review classification | `ManualReviewPolicy` (decision) vs `Order`/`ReviewRequirement` (lifecycle); both answers reachable — `POST /orders/{id}/approve-review` and `POST /orders/{id}/reject-review` | `ManualReviewPolicyTest`, `ReviewFlowTest` (end-to-end, both answers) |
| A terminal success, and the rule that guards it | `POST /orders/{id}/ship` → `Order.ship`; cancelling a `SHIPPED` order is refused with `RETURN_REQUIRED`, because undoing a dispatch is a return | `OrderLifecycleTransitionsTest`, `ExceptionContractTest` (the 409, over HTTP) |
| Domain events (subscriber in application layer) | `OrderFulfilmentStarter` on `OrderReadyForFulfilmentEvent` | `ReviewFlowTest`, `OrderingFlowTest` |
| Integration events + transactional outbox → Kafka → inbox | `OrderReadyForFulfilment`, `PaymentRequested` (`*-api`); `PlaceOrderHandler`/`FulfilmentTrigger` publish | `OutboxAtomicityTest`, `IntegrationEventTransportTest` |
| Anti-corruption layers | `StockAvailabilityGateway` (ordering port + infra adapter); `OrderReadyForFulfilmentListener`, `PaymentRequestedListener` (inbound ACLs) | `OrderingFlowTest`, `PaymentCompensationFlowTest` |
| Multi-aggregate transaction that is really all-or-nothing | `ReserveStockHandler` decides over a `Map<Sku, Stock>` in memory, then writes; `ReserveStock` merges lines repeating a SKU | `StockReservationAtomicityTest` |
| Cross-aggregate invariant held strongly (not eventually) | `Customer.reserveCredit` + version column; committed on placement, released by `CustomerCredit` on every cancellation | `CreditLimitTest`, `CustomerTest` |
| The quickstart below actually runs | this README's own `curl` commands, parsed rather than copied | `ReadmeQuickstartTest` |
| Durable process manager | `OrderFulfilmentDefinition` (pure decision), `OrderFulfilmentCodecs` (a `ProcessSerializationCatalog` for 12 payloads + one hand-written codec where a sealed domain type forbids Jackson annotations), `RuntimeOrderFulfilmentProcess` | `OrderFulfilmentDefinitionTest` (unit), `OrderingFlowTest` (e2e) |
| Ordered compensation (release then cancel) | `OrderFulfilmentDefinition` compensation branches | `PaymentCompensationFlowTest` |
| Deadlines (a wait that can end) | `OrderFulfilmentDefinition` arms/cancels the `STOCK`, `PAYMENT` and `STOCK_RELEASE` deadlines; `*TimedOut` inputs | `OrderFulfilmentDefinitionTest` (unit), `PaymentTimeoutFlowTest` and `StockReservationTimeoutFlowTest` (e2e, each context silent in turn) |
| Cursor-paged read model (no aggregate loaded) | `OrderQueries` + `OrderListMapper` → `GET /orders?customerId=`; `Slice`/`Cursor` | `OrderListPagingTest`, `FindCustomerOrdersHandlerTest` |
| HTTP idempotency (a retry that does not buy twice) | `aipersimmon.ddd.web.idempotency` + `-web-store-mybatis-plus` on `POST /orders` | `OrderIdempotencyTest` |
| Optimistic-lock conflict rendered as 409 | version-checked `save` → `ConcurrencyConflictException` → problem document | `ConcurrentApprovalTest`, `ConcurrentAggregateWriteTest` |
| Dead letters and operator replay | `DeadLetterOpsController` over the `DeadLetters` + `DeadLetterStore` ports (`GET /ops/dead-letters` cursor-paged, `GET /ops/dead-letters/{id}`, `POST /ops/dead-letters/{id}/replay`) | `DeadLetterReplayTest` |
| `Specification` answers, `Invariant` refuses | `CancellableByCustomer` (on `OrderSnapshot.cancellableByCustomer`) vs `OrderLifecyclePolicy`; `POST /orders/{id}/cancel` — reachable for every order, not just reviewed ones | `CancellableByCustomerTest`, `SelfCancelTest`, `SelfCancelDuringReservationTest` |
| Business-key idempotency (decide once, announce every time) | `AuthorizePaymentHandler` + `PaymentOperations` over `payment_operations` — claimed in the command's own transaction, so a rolled-back publish takes the claim with it | `AuthorizePaymentIdempotencyTest` (unit), `PaymentOperationAtomicityTest` (e2e) |
| Payment authorization rule | `AuthorizationPolicy`, `PaymentDecision` | `AuthorizationPolicyTest`, `PaymentDecisionTest` |
| Web error contract (RFC 9457) | `OrderingProblemCatalog` (composition root) | `ExceptionContractTest` |
| Persistence (MyBatis / PostgreSQL) | `ordering-infrastructure`, `inventory-infrastructure` (`MyBatis*` mappers); schema in `start/src/main/resources/db/migration/`, **one directory per bounded context** with a version major each — `ordering/` (`1.x`), `inventory/` (`2.x`), `payment/` (`3.x`). See that directory's `README.md` | `OutboxAtomicityTest`, `MigrationContentTest`, `TableRetentionTest` |
| Replaceable business policies | `ManualReviewPolicy` + `RestrictedSkuReviewPolicy` (ordering), `AuthorizationPolicy` + `CeilingAuthorizationPolicy` (payment); bound in `OrderingPolicyConfig` / `PaymentPolicyConfig` | `ManualReviewPolicyTest`, `AuthorizationPolicyTest` (incl. a non-default ceiling) |
| Published-event schema evolution | `OrderReadyForFulfilment` (v2) alongside `OrderReadyForFulfilmentV1`, both consumed by `OrderReadyForFulfilmentListener` | `OrderReadyForFulfilmentVersionsTest` |
| Architecture rules (layering, context isolation, event placement) | `AiPersimmonDddRules` applied over this project's base package | `ArchitectureTest`, `PackageInfoTest` |

## Replaceable policies

Two rules in this project are the ones a real deployment is most likely to need different, so both
are ports with a configurable default rather than classes:

| Rule | Change the *value* | Change the *rule* |
|---|---|---|
| Which orders need manual review | `ordering.review.restricted-skus` | declare a `ManualReviewPolicy` bean |
| Which payments are authorized | `payment.authorization.ceiling-minor` | declare an `AuthorizationPolicy` bean |

Both defaults are `@ConditionalOnMissingBean`, so supplying your own is additive — nothing has to be
deleted or edited. The two levels exist because they cost different amounts: a threshold is a
property, a fraud service is a bean.

Both used to be `new`ed into a `private static final` field of their handler, which meant the two
most business-variable rules in the codebase were the two you could not change without editing a use
case. The ports live in the `*-domain` modules (framework-free, so they take their configuration as
constructor arguments and know nothing about YAML) and the binding lives in `start`, next to the
other composition decisions.

The rule that is deliberately **not** replaceable is `OrderLifecyclePolicy`: it is an aggregate
invariant, and the aggregate must not let anyone swap out the arbiter of its own legality.

## Published-event schema evolution

`OrderReadyForFulfilment` is at `version = 2`, and `OrderReadyForFulfilmentV1` is kept beside it. It
is the only event here that has been through a revision, and it exists to show what the operation
costs — schema evolution of a published contract is the hardest part of integrating bounded contexts,
and every other event sitting at `version = 1` demonstrated nothing about it.

The mechanism: the catalog keys classes by `(name, version)`, so both revisions register under one
logical name and both stay on one topic (a version bump is not a new topic — splitting would break
per-order ordering for exactly the window the migration has to survive). Only v2 is ever published;
v1 exists to be read, because at the moment of a rollout the topic still holds v1 messages and the
inbox may still redeliver older ones.

Where the difference is absorbed: `OrderReadyForFulfilmentListener` in `inventory-adapter`, which has
one listener per revision funnelling into one internal call. **`ReserveStock` and
`ReserveStockHandler` did not change and do not know a version exists** — that is what an
anticorruption layer is for, and it is the property `OrderReadyForFulfilmentVersionsTest` pins. No
end-to-end test can cover the retired path, because a producer only ever publishes the current
revision.

## What each capability cost to adopt

This project is also the library's first consumer that assembles the components together, so what it
cost to use them is worth recording. "Lines" counts what a consuming project writes, excluding tests
and comments. "Implicit pairing" is the knowledge you had to already have — the part no dependency
list tells you.

| Capability | Lines | New concepts | Implicit pairing | Friction found |
|---|---|---|---|---|
| Versioned aggregate write | ~0 | — | row implements `VersionedRow` **and** carries `@Version`; table needs `version DEFAULT 1` | none — the base class carries it |
| Conflict → 409 | 0 | — | none | the lock 409 is `about:blank` with no `code`, while a *domain* 409 is coded: the one a client should retry is the one it cannot recognise |
| HTTP idempotency | ~8 (yaml) | `Idempotency-Key` | a MyBatis-Plus app still takes a **`-jdbc`** store module; `flyway.components` needs `web-store` | three library defects surfaced (a startup failure; a replayed `201` that lost its `Location`) — all fixed upstream |
| Cursor paging + read model | ~90 | `Slice`, `Cursor` | the cursor can be the id **only because** ids are UUIDv7 — and a page costs the page **only because** an index covers `(tenant_id, customer_id, id DESC)`; the two are separate, and losing either is silent | a missing query param used to be a 500 — fixed; no covering index existed and the paging tests passed anyway — fixed in `V4` |
| Deadlines | ~25 | `DeadlineName`, `ScheduleDeadline`, `CancelDeadline` | arm and cancel on every branch leaving the step; due time must come from `context.now()` | none — this API fits |
| Dead letters + replay | ~45 | `DeadLetters` (read), `DeadLetterStore` (replay) | none | `replay(eventId)` shipped with no way to obtain an `eventId` — fixed by the read port, which deleted this project's hand-written query |
| `Specification` | ~30 | `Specification` | keep one statement of the rule, or the answer and the refusal drift | none |
| Test infrastructure | ~2 | `@ServiceConnection` | — | Kafka — the library's own transport — was the one container the module did not provide; fixed |

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
| Hand-mapped SQL for the application's own tables | The framework's tables go through MyBatis-Plus; your own need not. Anything on the same `DataSource` joins the same transaction, and showing a second mapping style would double the build for a story already told. |
| Redis web stores, rate limiting, replay protection | Idempotency already demonstrates the edge-store wiring; the other two differ only in what they count. |
| `sendAs` / `publishAs` | The replay path preserves identity structurally — the row keeps its id — so nothing here needed the explicit carry-an-existing-identity entry points. They remain unexercised. |
| A second topology (modulith, microservice) | Dropped in `605fab3`; the transport story is the same one, packaged differently. |
| `instance.max-lifetime` | See "Known demo gaps" below. |

Try it: `SKU-RESTRICTED` is on the review watchlist (`ordering.review.restricted-skus`), so an order
containing it is held in `AWAITING_REVIEW` until `POST /orders/{id}/approve-review` clears it — see
`ReviewFlowTest`. The watchlist is configuration and the rule behind it is a bean, so both the list
and the whole policy are replaceable without editing a handler — see "Replaceable policies" below.

## Copying this: what you may leave out

This project runs every building block at once because it is the worked example for all of them.
That makes it a catalogue, not a serving suggestion — eighteen modules is what *demonstrating
everything* costs, not what a three-step business flow needs. Copying the whole shape into a
context that has not earned it is the main way to get this scaffold wrong. When you copy, subtract:

- **`payment` is the floor, and the floor is low.** Its domain layer is four classes and no
  aggregate: a policy, a sealed decision, two value objects. If your context's rules fit in a
  policy and a decision, that *is* the domain layer — manufacturing an aggregate to look more
  DDD adds a lifecycle where there is no lifecycle. Start every new context from the payment
  shape and let it grow into the ordering shape only when invariants spanning state actually
  appear.
- **No second deployable reacting to your events? Drop Kafka.** Without
  `-starter-messaging-kafka`, integration events are delivered in process, synchronously, in the
  publisher's transaction — no broker, no inbox, no eventual consistency to explain. The handler
  code is identical either way, which is precisely why the broker can be added later instead of
  up front.
- **No long-lived flow waiting on other contexts? Drop `ordering-process`.** The process manager
  earns its tables when a flow must survive a restart while waiting for someone else. A request
  that completes inside one transaction never needs it — and "we might need it later" is
  satisfied by adding it later.
- **A context that owns no tables needs almost nothing.** A pure calculation or read-side
  projection takes the bare starter: buses, in-process events, ids, the web contract — no
  storage bundle, no Flyway components, none of the four tables. See the upstream
  CHOOSING-MODULES.md ([Upstream documentation](#upstream-documentation)) for the decision path;
  the four dependencies this project uses are the
  *maximum* a service normally reaches, not the entry fee.
- **Five modules per context is release granularity, not virtue.** The split exists so the
  ArchUnit rules can price every dependency edge and so `-api` can be versioned to other
  contexts. A context nothing else consumes can fold `-api` away; a team that trusts its
  package discipline (and keeps the ArchUnit gate) can start with fewer modules and split when
  a boundary needs enforcing. The rules care about the *edges*, not the module count.
- **Three contexts demonstrate three shapes** — a full aggregate context (ordering), a
  contention-boundary context (inventory), a policy-only context (payment). They are a spectrum
  to place yourself on, not a minimum headcount for a service.

What you should *not* subtract: the outbox under externalized events (that is the delivery
guarantee, not ceremony), idempotent consumption wherever a message can arrive twice, and the
ArchUnit gate — the rules are what keeps the claimed architecture true after month three.

## Intentional design decisions worth knowing

- **No public `confirm` endpoint.** Confirming is an *internal* step of the fulfilment process
  (dispatched only after payment is authorized). Exposing it would let a client bypass the process
  manager's preconditions, so `OrderController` exposes no `confirm` at all. Approving a
  held review *is* a legitimate operator action, and hosts the 404/409 error-contract demos.
- **Payment speaks one word — *authorize*.** This reference demonstrates the authorization step
  only, not a later capture, so `AuthorizePayment`/`AuthorizationPolicy`/`PaymentAuthorized` are used
  end to end (no "charge"/"capture" mixing).
- **Payment owns no persisted aggregate.** Its only technical state is an at-most-once operation-
  dedupe log behind the `PaymentOperations` port, held in `payment.payment_operations` and
  implemented by `MyBatisPaymentOperations` in `payment-infrastructure`. It is an outbound adapter,
  so it lives in the infrastructure layer, not the application layer — and it is a table rather than
  a map because claiming an operation and announcing its outcome have to be one commit.
  A `ConcurrentHashMap` could not be rolled back, so a failed transaction kept the
  claim and lost the authorization permanently.
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

- **The max-lifetime backstop is not armed.** Every step that waits on another context now has its
  own deadline (see the flow above), which covers the cases that can be anticipated.
  `instance.max-lifetime` is a blunter, whole-instance cap for flows
  that stall somewhere nobody anticipated, and arming it means deciding what a lifetime-exceeded
  order should do — compensate from an arbitrary step, or suspend for an operator. That is a real
  design choice, not a wiring exercise, so it is left out rather than guessed at.
  `OrderFulfilmentDefinition.react` still guards the `MaxLifetimeExceeded` input so enabling it
  cannot crash the definition: it rejects cleanly and the runtime suspends the instance.

## Upstream documentation

This project is generated from the `multi-module` archetype and ships on its own, so the AiPersimmon
DDD library's own documents are **not** in this tree. This section is the single place that knows
where they are: nothing else here names a file it does not have, and adopting the project means
rewriting these four URLs and nothing else.

| Document | What it answers |
|---|---|
| [CHOOSING-MODULES.md](https://github.com/ryan-alexander-zhang/ai-native-project-template/blob/lang/java/ddd/aipersimmon-ddd/CHOOSING-MODULES.md) | Which `aipersimmon-ddd-*` modules to declare, and what each one drags in. Start here when adding a capability (a second persistence backend, Redis edge stores, a different messaging transport). |
| [CONFIGURATION.md](https://github.com/ryan-alexander-zhang/ai-native-project-template/blob/lang/java/ddd/aipersimmon-ddd/CONFIGURATION.md) | Every `aipersimmon.ddd.*` property, and the production checklist — the settings a deployment is expected to decide rather than inherit (outbox lease budget and cleanup, inbox retention, tenancy policy). `start/src/main/resources/application.yml` answers that checklist inline; this is where the questions come from. |
| [ARCHITECTURE.md](https://github.com/ryan-alexander-zhang/ai-native-project-template/blob/lang/java/ddd/ARCHITECTURE.md) | The layering and dependency rules the `ArchitectureTest` in `start` enforces. Note it sits at the library repository's root, not under `aipersimmon-ddd/`. |
| [README.md](https://github.com/ryan-alexander-zhang/ai-native-project-template/blob/lang/java/ddd/aipersimmon-ddd/README.md) (library) | What the building blocks are and how they fit together. |

The library lives on the `lang/java/ddd` branch; it is not on `main`. Keep the ref when you rewrite
these for a fork, or the links resolve to a different repository layout.

**The rule these links exist to keep:** anywhere else in this tree that mentions one of these
documents — a comment in `application.yml`, a javadoc, a bullet above — must mark it as *upstream*
and must not write it as a local path. The section title is worth naming (it is the token you will
search for); the bare filename is not, because in a generated project it resolves to nothing.
`DocumentationReferenceTest` in `start` enforces this.
