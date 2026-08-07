# Sequence diagrams — multi-module

Eight flows, each one traced through the actual call sites and each one pinned by a named test. The
participant list is deliberately literal: where a message crosses the outbox → Kafka → inbox path it
is drawn crossing it, because "published" and "delivered" are different moments and most of the
interesting behaviour lives in the gap.

Colour convention: `ordering` participants are the core, `inventory` and `payment` are the other two
contexts, and the transport is drawn as one participant even though it is a relay, a broker and a
dedupe table.

| # | Flow | Verified by |
|---|---|---|
| [1](#1-happy-path) | Happy path — placed → reserved → authorized → confirmed | `OrderingFlowTest` |
| [2](#2-manual-review-both-answers) | Manual review, both answers | `ReviewFlowTest` |
| [3](#3-reservation-failure--compensation) | Reservation failure → compensation | `OrderingFlowTest` |
| [4](#4-payment-decline--ordered-compensation) | Payment decline → ordered compensation | `PaymentCompensationFlowTest` |
| [5](#5-payment-timeout--the-same-path-as-a-decline) | Payment timeout → same path as a decline | `PaymentTimeoutFlowTest` |
| [6](#6-stock-timeout--cancel-outright) | Stock timeout → cancel outright | `StockReservationTimeoutFlowTest` |
| [7](#7-the-self-cancel-race) | Self-cancel racing the reservation | `SelfCancelDuringReservationTest` |
| [8](#8-ship-and-the-refusal-that-follows) | Ship, and the refusal that follows | `OrderLifecycleTransitionsTest`, `ExceptionContractTest` |

Plus two supporting views: [the outbox path in detail](#the-outbox-path-in-detail) and
[payment idempotency](#payment-idempotency-under-redelivery).

---

## 1. Happy path

```mermaid
sequenceDiagram
    autonumber
    actor C as Customer
    participant CTRL as OrderController<br/>(ordering-adapter)
    participant BUS as CommandBus<br/>(library)
    participant PRE as StockAvailabilityPrecheck<br/>(CommandPrecheck)
    participant POH as PlaceOrderHandler
    participant GW as StockAvailabilityGateway<br/>→ StockAvailabilityApi
    participant CUST as Customer<br/>(aggregate)
    participant ORD as Order<br/>(aggregate)
    participant FT as FulfilmentTrigger
    participant STR as OrderFulfilmentStarter
    participant PM as OrderFulfilment<br/>Definition + runtime
    participant TX as outbox → Kafka → inbox
    participant INV as inventory
    participant PAY as payment

    C->>CTRL: POST /orders<br/>X-Tenant-Id, Idempotency-Key
    CTRL->>BUS: send(PlaceOrder)
    Note over BUS: validation · tenancy
    BUS->>PRE: precheck slot — BEFORE the transaction interceptor
    PRE->>GW: check(distinct skus)
    GW->>INV: StockAvailabilityApi.check (sync, in-process)
    INV-->>GW: report(all available)
    Note over PRE,GW: fail-fast on availability only — the quantity<br/>reservation is still async. Outside the transaction<br/>on purpose: a slow answer holds no connection

    Note over BUS: @OperationLog<br/>opens ONE transaction
    BUS->>POH: handle(cmd, context)

    POH->>CUST: customers.findById
    POH->>POH: ManualReviewPolicy.assess → notRequired
    POH->>ORD: Order.place(id=UUIDv7, …, notRequired)
    Note over ORD: status = READY_FOR_FULFILMENT<br/>registers OrderPlacedEvent<br/>+ OrderReadyForFulfilmentEvent
    POH->>CUST: reserveCredit(order.total)
    POH->>CUST: customers.save (version-checked)
    POH->>FT: begin(order, context)
    FT->>ORD: orders.save → INSERT at version 1
    Note over ORD,STR: saving drains the registered domain events
    ORD-->>STR: OrderReadyForFulfilmentEvent (in-process, same tx)
    STR->>PM: readyForFulfilment(orderId)
    PM->>PM: start → AWAITING_STOCK<br/>+ arm STOCK deadline (PT1M)
    FT->>TX: publish OrderReadyForFulfilment v2<br/>(+ reservationDeadline) → outbox row
    Note over BUS,TX: COMMIT — aggregate rows, process row<br/>and outbox row are one transaction
    BUS-->>CTRL: orderId
    CTRL-->>C: 201 Created<br/>Location: /orders/{id}

    TX->>INV: OrderReadyForFulfilment (ordering.events)
    Note over INV: the ACL. A retired v1 message is carried forward by<br/>OrderReadyForFulfilmentV1Upcaster BEFORE dispatch, so<br/>OrderReadyForFulfilmentListener faces one revision and<br/>issues ReserveStock (inventory's own command)
    INV->>INV: DECIDE over a Map of Sku to Stock<br/>then WRITE + new Reservation (UUIDv7)
    INV->>TX: publish StockReserved (inventory.events)
    TX->>PM: StockReserved → process.stockReserved

    PM->>PM: AWAITING_STOCK + StockReserved<br/>→ AWAITING_PAYMENT
    Note over PM: effects: BeginFulfilment · RequestPayment<br/>CancelDeadline(STOCK) · ScheduleDeadline(PAYMENT, PT2M)
    PM->>BUS: DispatchCommand BeginFulfilment
    BUS->>ORD: beginFulfilment → FULFILMENT_IN_PROGRESS
    Note over ORD: the self-cancel window closes HERE,<br/>not at placement
    PM->>BUS: DispatchCommand RequestPayment(orderId, paymentOperationId)
    BUS->>TX: publish PaymentRequested (ordering.events)

    TX->>PAY: PaymentRequested
    Note over PAY: PaymentRequestedListener → AuthorizePayment<br/>claim (tenant, operationId) in payment_operations<br/>AuthorizationPolicy: 200 ≤ 50000 → Authorized
    PAY->>TX: publish PaymentAuthorized (payment.events)
    TX->>PM: PaymentAuthorized → process.paymentAuthorized

    PM->>PM: AWAITING_PAYMENT + PaymentAuthorized<br/>→ AWAITING_ORDER_CONFIRMATION
    PM->>BUS: DispatchCommand ConfirmOrder + CancelDeadline(PAYMENT)
    BUS->>ORD: confirm → CONFIRMED
    ORD-->>STR: OrderConfirmedEvent (in-process)
    STR->>PM: orderConfirmed(orderId)
    PM->>PM: → COMPLETED, outcome ORDER_CONFIRMED

    C->>CTRL: GET /orders/{id}
    CTRL-->>C: 200 {status: CONFIRMED,<br/>cancellableByCustomer: false}
```

**The one thing to take from this diagram** is where `FULFILMENT_IN_PROGRESS` is set: at step *"begin
fulfilment"*, driven by the process manager once a reservation actually exists — **not** at placement.
`FulfilmentTrigger` deliberately no longer advances the aggregate. Before that change a new order was
`INSERT`ed straight into `FULFILMENT_IN_PROGRESS`, no row ever held `READY_FOR_FULFILMENT`, and since
that is the state the self-cancel window is defined over, the window was unreachable for any order not
held for review.

**Three facts, three rows.** "Placed", "ready for fulfilment" and "fulfilment in progress" are
distinct, and each is a state a row actually holds. Asking for a reservation is not having one.

## 2. Manual review, both answers

```mermaid
sequenceDiagram
    autonumber
    actor C as Customer
    actor R as Review Operator
    participant CTRL as OrderController
    participant BUS as CommandBus
    participant H as Approve/Reject<br/>ReviewHandler
    participant ORD as Order
    participant CR as CustomerCredit
    participant FT as FulfilmentTrigger
    participant PM as process manager
    participant TX as outbox → Kafka → inbox
    participant INV as inventory

    C->>CTRL: POST /orders<br/>lines include SKU-RESTRICTED
    CTRL->>BUS: send(PlaceOrder)
    Note over BUS: ManualReviewPolicy.assess → Required
    BUS->>ORD: Order.place(…, required)
    Note over ORD: status = AWAITING_REVIEW<br/>registers ONLY OrderPlacedEvent
    BUS->>ORD: customer.reserveCredit + orders.save
    Note over ORD,PM: no OrderReadyForFulfilmentEvent, so no process starts<br/>and NOTHING is reserved
    CTRL-->>C: 201 Created

    alt Approved — POST /orders/{id}/approve-review
        R->>CTRL: approve-review
        CTRL->>BUS: send(ApproveReview)
        BUS->>H: ApproveReviewHandler
        H->>ORD: approveReview(ReviewDecisionRef(id, approved=true))
        Note over ORD: AWAITING_REVIEW → READY_FOR_FULFILMENT<br/>registers OrderReadyForFulfilmentEvent
        H->>FT: begin(order, context)
        FT->>PM: (via OrderFulfilmentStarter) start AWAITING_STOCK
        FT->>TX: publish OrderReadyForFulfilment
        CTRL-->>R: 204 No Content
        TX->>INV: reserve stock — flow 1 continues from here
    else Rejected — POST /orders/{id}/reject-review
        R->>CTRL: reject-review
        CTRL->>BUS: send(RejectReview)
        BUS->>H: RejectReviewHandler
        H->>ORD: cancel(ReviewRejected(ReviewDecisionRef(id, approved=false)))
        Note over ORD: OrderLifecyclePolicy: status must be AWAITING_REVIEW<br/>and the decision must belong to this order<br/>→ CANCELLED, category REVIEW_REJECTED
        H->>ORD: orders.save
        H->>CR: releaseFor(order)
        Note over H,CR: saved FIRST, then released — an order that<br/>refuses to cancel must not hand back credit<br/>it is still holding
        CTRL-->>R: 204 No Content
        Note over PM,INV: no process ever started and no stock was<br/>ever reserved, so there is nothing to compensate.<br/>Synchronous and complete.
    end
```

**Rejection is synchronous and needs no compensation**, and that is a consequence of holding the order
*before* reserving anything. The two answers are asymmetric in exactly the way the states are.

## 3. Reservation failure → compensation

```mermaid
sequenceDiagram
    autonumber
    participant PM as process manager<br/>AWAITING_STOCK
    participant TX as outbox → Kafka → inbox
    participant INV as ReserveStockHandler
    participant STOCK as Stock roots
    participant BUS as CommandBus
    participant ORD as Order
    participant CR as CustomerCredit

    TX->>INV: OrderReadyForFulfilment
    INV->>STOCK: DECIDE: load each SKU once,<br/>reserve in memory
    Note over INV,STOCK: line 2 wants 99 of SKU-2 (5 available)<br/>→ DomainException(inventory.insufficient-stock)
    Note over INV: nothing has been written, so there is nothing<br/>to undo and no reason to roll back
    INV->>TX: publish StockReservationFailed(orderId,<br/>code, reason) — the transaction COMMITS
    TX->>PM: process.stockReservationFailed

    PM->>PM: AWAITING_STOCK + StockReservationFailed<br/>→ COMPENSATING, AWAITING_ORDER_CANCELLATION
    Note over PM: ReservationFailureRef(<br/>failureId = causing envelope's messageId,<br/>orderId, code, reason)
    PM->>BUS: DispatchCommand CancelOrder(<br/>InventoryUnavailable(failure))<br/>+ CancelDeadline(STOCK)
    BUS->>ORD: cancel(InventoryUnavailable)
    Note over ORD: OrderLifecyclePolicy: status must be<br/>READY_FOR_FULFILMENT or FULFILMENT_IN_PROGRESS,<br/>and the failure must belong to this order
    Note over ORD: → CANCELLED, category INVENTORY_UNAVAILABLE
    BUS->>ORD: orders.save
    BUS->>CR: releaseFor(order) — credit returned
    ORD-->>PM: OrderCancelledEvent → process.orderCancelled
    PM->>PM: → COMPLETED, outcome ORDER_CANCELLED
```

**Decide-then-write is what makes the failure clean.** Every load and every domain decision happens
before the first `save`, so a shortfall on any line throws with nothing written and the transaction
commits carrying only the failure event. A throw from the *write* phase is a different animal — it is
technical, it is deliberately **not** caught, and it rolls the transaction back so the delivery is
retried. Catching it would commit exactly the partial deduction the split prevents.

**And that is why the STOCK deadline exists.** The rolled-back case publishes *nothing*, so from
ordering's side it is indistinguishable from inventory being down. Flow 6 is what happens then.

## 4. Payment decline → ordered compensation

```mermaid
sequenceDiagram
    autonumber
    participant PM as process manager<br/>AWAITING_PAYMENT
    participant TX as outbox → Kafka → inbox
    participant PAY as AuthorizePaymentHandler
    participant BUS as CommandBus
    participant INV as ReleaseStockHandler
    participant ORD as Order
    participant CR as CustomerCredit

    TX->>PAY: PaymentRequested(amountMinor = 60000)
    Note over PAY: claim (tenant, operationId)<br/>AuthorizationPolicy: 60000 > 50000<br/>→ Declined(payment.amount-exceeds-ceiling)
    PAY->>TX: publish PaymentDeclined(orderId, code, reason)
    TX->>PM: process.paymentDeclined

    PM->>PM: AWAITING_PAYMENT + PaymentDeclined<br/>→ COMPENSATING, AWAITING_STOCK_RELEASE
    Note over PM: state.declined(code, evidenceId = causing<br/>envelope's messageId) — the decline event's id is<br/>REMEMBERED, because the eventual cancellation<br/>happens under a different cause
    PM->>BUS: DispatchCommand RequestStockRelease(orderId, reservationId)<br/>+ CancelDeadline(PAYMENT)<br/>+ ScheduleDeadline(STOCK_RELEASE, PT1M)
    BUS->>TX: publish StockReleaseRequested

    TX->>INV: StockReleaseRequested → ReleaseStock
    Note over INV: reservation.markReleased() → true (first time)<br/>hand each held quantity back to its Stock
    INV->>TX: publish StockReleased(orderId, reservationId)
    TX->>PM: process.stockReleased

    PM->>PM: AWAITING_STOCK_RELEASE + StockReleased<br/>→ AWAITING_ORDER_CANCELLATION
    Note over PM: NOW the reason can be built:<br/>PaymentDeclinedAfterStockReleased(<br/>PaymentDeclineRef(remembered decline id, …),<br/>StockReleaseRef(this event's id, …))
    PM->>BUS: DispatchCommand CancelOrder(reason)<br/>+ CancelDeadline(STOCK_RELEASE)
    BUS->>ORD: cancel(PaymentDeclinedAfterStockReleased)
    Note over ORD: OrderLifecyclePolicy: status must be<br/>FULFILMENT_IN_PROGRESS, and BOTH refs must<br/>belong to this order
    Note over ORD: → CANCELLED, category PAYMENT_DECLINED
    BUS->>CR: releaseFor(order)
    ORD-->>PM: OrderCancelledEvent
    PM->>PM: → COMPLETED, outcome ORDER_CANCELLED
```

**Compensation is ordered, and the type system is what orders it.**
`PaymentDeclinedAfterStockReleased` cannot be constructed without a `StockReleaseRef`, so the
cancellation is *unreachable* until the release has actually happened. A bare
`PAYMENT_DECLINED` enum would have asserted the outcome with no proof the compensation ran.

**Two distinct evidence ids, and neither is a business key.** The decline ref keeps the remembered
decline-event id; the release ref takes the stock-released event's id. Keying them on
`orderId`/`reservationId` would have made them collide and untraceable.

## 5. Payment timeout → the same path as a decline

```mermaid
sequenceDiagram
    autonumber
    participant PM as process manager<br/>AWAITING_PAYMENT
    participant DW as deadline worker<br/>(poll 1s)
    participant PAY as payment
    participant BUS as CommandBus
    participant INV as inventory
    participant ORD as Order

    Note over PM: PAYMENT deadline armed at<br/>context.now() + PT2M
    PM--xPAY: PaymentRequested was published…
    Note over PAY: …and nothing comes back.<br/>A technical failure, a broker gap, or a<br/>provider that simply does not answer.

    DW->>PM: PaymentTimedOut(orderId) — the timer fires
    PM->>PM: AWAITING_PAYMENT + PaymentTimedOut<br/>→ COMPENSATING, AWAITING_STOCK_RELEASE
    Note over PM: state.declined(PAYMENT_TIMEOUT,<br/>evidenceId = the TIMER's own delivery id)<br/>No CancelDeadline — this decision IS the deadline
    PM->>BUS: DispatchCommand RequestStockRelease<br/>+ DispatchCommand RequestPaymentVoid<br/>+ ScheduleDeadline(STOCK_RELEASE)

    BUS->>PAY: PaymentVoidRequested → VoidPayment
    Note over PAY: settles the race on the operation row:<br/>nothing recorded → record Voided (refuse in advance)<br/>Authorized → markVoided, release the hold<br/>Declined/Voided → nothing held, fall through

    Note over PM,ORD: the rest is the decline's path:<br/>release the stock, then cancel with<br/>PaymentDeclinedAfterStockReleased
    BUS->>INV: ReleaseStock → StockReleased
    INV->>PM: process.stockReleased
    PM->>BUS: CancelOrder(PaymentDeclinedAfterStockReleased)
    BUS->>ORD: → CANCELLED, category PAYMENT_DECLINED
```

**Silence is an answer, and it is *almost* the same answer.** The customer's position is identical
however the payment failed to happen, so the compensation is the decline's. Only the recorded code
differs — `PAYMENT_TIMEOUT` rather than the payment context's own — which is what lets an operator
afterwards tell "payment said no" from "payment said nothing".

**The one addition a decline does not need is the void.** A decline is payment's own recorded
decision and can never later authorize. A timeout is only silence: the authorization may still
complete after this flow has moved on, and a terminal instance reacts to nothing, so the hold would
be orphaned for good. `RequestPaymentVoid` goes out *eagerly*, in the same decision that abandons
the wait, which makes the abandonment mutual rather than unilateral. Nothing waits on it — there is
no outcome event — because by the time ordering asks, it has already stopped listening.

**The evidence id is the timer's own delivery,** so the cancellation names the firing rather than a
decline that never happened.

## 6. Stock timeout → cancel outright

```mermaid
sequenceDiagram
    autonumber
    participant PM as process manager<br/>AWAITING_STOCK
    participant DW as deadline worker
    participant INV as inventory
    participant BUS as CommandBus
    participant ORD as Order
    participant CR as CustomerCredit

    Note over PM: STOCK deadline armed by start(),<br/>at context.now() + PT1M
    PM--xINV: OrderReadyForFulfilment was published…
    Note over INV: …and the handler hit an optimistic-lock<br/>conflict / a validation error / a database<br/>outage. It threw, the transaction rolled back,<br/>and NOTHING was published.

    DW->>PM: StockReservationTimedOut(orderId)
    PM->>PM: AWAITING_STOCK + StockReservationTimedOut<br/>→ COMPENSATING, AWAITING_ORDER_CANCELLATION
    Note over PM: SAME branch as StockReservationFailed<br/>(cancelForInventory), with code STOCK_TIMEOUT<br/>and no CancelDeadline — this decision IS the deadline
    PM->>BUS: DispatchCommand CancelOrder(<br/>InventoryUnavailable(ReservationFailureRef(<br/>timer's id, orderId, STOCK_TIMEOUT, …)))
    BUS->>ORD: cancel → CANCELLED, INVENTORY_UNAVAILABLE
    BUS->>CR: releaseFor(order)
    ORD-->>PM: OrderCancelledEvent → COMPLETED / ORDER_CANCELLED
```

**Nothing was reserved, so there is nothing to release** — the timeout cancels outright, down exactly
the path a refusal takes. Compare with flow 5, where stock *is* held and so a release must come first.

### The one timeout that cannot end its wait

`AWAITING_STOCK_RELEASE` is the interesting case, and it is the only one that behaves differently:

```mermaid
sequenceDiagram
    autonumber
    participant PM as process manager<br/>AWAITING_STOCK_RELEASE
    participant DW as deadline worker
    participant BUS as CommandBus
    participant INV as inventory

    Note over PM: STOCK_RELEASE deadline armed, PT1M
    DW->>PM: StockReleaseTimedOut(orderId)
    Note over PM: Cancelling from here needs a StockReleaseRef —<br/>proof the stock came back. A timeout is PRECISELY<br/>the absence of that proof.
    PM->>PM: stay at AWAITING_STOCK_RELEASE (COMPENSATING)
    PM->>BUS: DispatchCommand RequestStockRelease (again)<br/>+ ScheduleDeadline(STOCK_RELEASE) (re-armed)
    BUS->>INV: ReleaseStock
    Note over INV: idempotent: markReleased() is already true,<br/>so no double hand-back — but StockReleased is<br/>published anyway, so the wait can resolve
    loop until inventory answers
        DW->>PM: StockReleaseTimedOut → ask again
    end
    INV->>PM: StockReleased → cancel with real evidence
```

Rescheduling by *name* supersedes the previous generation, so neither the repeated request nor the
timer accumulates anything. It is **deliberately unbounded**: giving up would mean recording that
stock came back when it has not. This is the one design decision the evidence-bearing
`CancellationReason` earns its complexity for — a looser model would have "recovered" here by
declaring released stock that is still held. The exposure is visibility, not correctness, and it is
recorded as an open hotspot.

## 7. The self-cancel race

The customer's window overlaps the reservation, so a cancellation can commit while inventory is still
working. The cancellation wins.

```mermaid
sequenceDiagram
    autonumber
    actor C as Customer
    participant CTRL as OrderController
    participant BUS as CommandBus
    participant ORD as Order
    participant CR as CustomerCredit
    participant PM as process manager<br/>AWAITING_STOCK
    participant TX as outbox → Kafka → inbox
    participant INV as inventory

    Note over PM,INV: OrderReadyForFulfilment is in flight —<br/>the order is READY_FOR_FULFILMENT
    C->>CTRL: POST /orders/{id}/cancel?customerId=CUST-1
    CTRL->>BUS: send(CancelOwnOrder)
    BUS->>ORD: cancel(CustomerRequested(CUST-1))
    Note over ORD: OrderLifecyclePolicy → CancellableByCustomer<br/>.BEFORE_FULFILMENT: status is READY_FOR_FULFILMENT,<br/>and the caller owns the order → allowed
    Note over ORD: → CANCELLED, category CUSTOMER_REQUESTED
    BUS->>CR: releaseFor(order) — credit returned
    ORD-->>PM: OrderCancelledEvent → process.orderCancelled
    PM->>PM: AWAITING_STOCK + OrderCancelled<br/>→ COMPENSATING, AWAITING_STOCK_ORDER_CANCELLED
    Note over PM: nothing can be concluded yet — the reservation<br/>may already exist, may be about to, or may fail.<br/>The STOCK deadline stays ARMED.
    CTRL-->>C: 204 No Content

    alt Inventory succeeds anyway
        TX->>INV: (already in flight) ReserveStock
        INV->>TX: StockReserved
        TX->>PM: process.stockReserved
        PM->>PM: → AWAITING_STOCK_RELEASE_ORDER_CANCELLED
        Note over PM: reserved for an order that no longer exists:<br/>hand it straight back. NO BeginFulfilment,<br/>NO RequestPayment — the cancellation stands.
        PM->>BUS: RequestStockRelease + CancelDeadline(STOCK)<br/>+ ScheduleDeadline(STOCK_RELEASE)
        BUS->>INV: ReleaseStock → StockReleased
        INV->>PM: process.stockReleased
        PM->>PM: → COMPLETED, ORDER_CANCELLED
        Note over PM: no second CancelOrder — the order is<br/>already in its terminal state
    else Inventory fails or times out
        TX->>PM: StockReservationFailed / StockReservationTimedOut
        PM->>PM: → COMPLETED, ORDER_CANCELLED
        Note over PM: nothing was ever reserved and the order is<br/>already cancelled, so there is no compensation<br/>to run and no command to send. Simply finished.
    end
```

**Two `*_ORDER_CANCELLED` steps exist for exactly this.** They are the "order is already terminal, but
stock might not be" states, and each has precisely two ways out — neither of which touches the order.

**A residual variant.** The same cancellation can commit while the flow is moving from
`AWAITING_STOCK` to `AWAITING_PAYMENT`, in which case `BeginFulfilment` finds a cancelled order and
returns without doing anything (it is idempotent on `FULFILMENT_IN_PROGRESS` and `CANCELLED`). The
`AWAITING_PAYMENT + OrderCancelled` branch then releases the held stock and finishes, again with no
second `CancelOrder`.

That branch has **one effect the `AWAITING_STOCK` one does not: `RequestPaymentVoid`**. By then
`PaymentRequested` is already out, so payment may be about to authorize — or may already have — for
an order that no longer exists. The void goes out in the same decision that abandons the wait,
because once this flow reaches its terminal step it reacts to nothing and a late `PaymentAuthorized`
would leave the hold orphaned. See [§5](#5-payment-timeout--the-same-path-as-a-decline) for how
payment settles the race on its operation row.

## 8. Ship, and the refusal that follows

```mermaid
sequenceDiagram
    autonumber
    actor D as Dispatch Operator
    actor C as Customer
    participant CTRL as OrderController
    participant BUS as CommandBus
    participant ORD as Order<br/>(CONFIRMED)

    D->>CTRL: POST /orders/{id}/ship
    CTRL->>BUS: send(ShipOrder)
    BUS->>ORD: ship()
    Note over ORD: Transitions table: CONFIRMED → SHIPPED<br/>registers OrderShippedEvent
    CTRL-->>D: 204 No Content

    C->>CTRL: POST /orders/{id}/cancel?customerId=CUST-1
    CTRL->>BUS: send(CancelOwnOrder)
    BUS->>ORD: cancel(CustomerRequested)
    Note over ORD: OrderLifecyclePolicy checks status == SHIPPED<br/>FIRST, before branching on the reason — the rule<br/>holds regardless of WHY
    ORD--xBUS: DomainException(ordering.return-required)
    BUS--xCTRL: propagates
    CTRL-->>C: 409 Conflict<br/>application/problem+json<br/>{code: ordering.return-required}
```

**Undoing a dispatch is a return, not a cancellation.** The refusal is coded and carries its rule,
which is what `ExceptionContractTest` asserts over HTTP. The reverse flow it points at does not exist
yet — recorded as an opportunity in the Event Storming model.

---

## The outbox path in detail

Every `TX` participant above collapses this. It matters because it is where "published" and
"delivered" separate.

```mermaid
sequenceDiagram
    autonumber
    participant H as any command handler
    participant IE as IntegrationEvents
    participant DB as PostgreSQL
    participant RELAY as outbox relay<br/>(ShedLock lease, 100/batch)
    participant K as Kafka
    participant CONS as Kafka consumer
    participant INBOX as inbox
    participant L as @EventListener<br/>(inbound ACL)
    participant DLQ as dead letters

    rect rgb(240, 248, 255)
    Note over H,DB: ONE transaction
    H->>IE: publish(event, context)
    IE->>DB: INSERT outbox row<br/>(payload, EventType, topic, tenant, causal chain)
    H->>DB: aggregate INSERT/UPDATE (version-checked)
    Note over H,DB: COMMIT — or neither happens
    end

    loop poll
        RELAY->>DB: claim rows under a per-row lease<br/>(head of each aggregate only, PT5M)
        RELAY->>K: send to @Externalized topic,<br/>key = subject() = orderId
        alt delivered
            RELAY->>DB: mark sent
        else exhausted its attempts
            RELAY->>DLQ: dead letter
            Note over DLQ: GET /ops/dead-letters<br/>POST /ops/dead-letters/{id}/replay
        end
    end

    K->>CONS: consume
    CONS->>INBOX: dedupe by event id
    alt first delivery
        INBOX->>L: EventEnvelope of the payload type
        L->>L: InboundEvents.commandContext(envelope)<br/>→ translate to this context's own command
    else already seen
        Note over INBOX: dropped — at-least-once delivery<br/>made at-most-once effect
    end
```

Three properties follow from this shape, and every flow above depends on them:

1. **Atomicity.** The aggregate write and the outbox row commit together, so an event cannot exist
   for a state change that rolled back — nor be lost for one that committed. `OutboxAtomicityTest`.
2. **At-least-once, plus dedupe.** The inbox makes redelivery harmless at the transport level; the
   handlers make it harmless at the business level too (`markReleased`, `payment_operations`, the
   process manager's `ignore` arm). Belt *and* braces, because the two protect against different
   things.
3. **Ordering per order.** `subject()` is always the `orderId`, so it is the partition key and every
   event about one order stays ordered relative to the others.

## Payment idempotency under redelivery

The narrowest and most instructive flow in the codebase.

```mermaid
sequenceDiagram
    autonumber
    participant TX as inbox
    participant H as AuthorizePaymentHandler
    participant OPS as payment.payment_operations<br/>(tenant_id, operation_id)
    participant POL as AuthorizationPolicy
    participant IE as IntegrationEvents

    rect rgb(240, 255, 240)
    Note over TX,IE: first delivery
    TX->>H: AuthorizePayment(operationId = O1)
    H->>OPS: find(O1) → empty
    H->>POL: decide(amountMinor, currency)
    POL-->>H: Authorized
    H->>OPS: record(O1, Authorized) — INSERT, the CLAIM
    H->>IE: publish PaymentAuthorized
    Note over H,IE: same transaction — a rolled-back publish<br/>takes the claim with it
    end

    rect rgb(255, 250, 240)
    Note over TX,IE: redelivery
    TX->>H: AuthorizePayment(operationId = O1)
    H->>OPS: find(O1) → Authorized
    Note over H,POL: the policy is NOT re-run. A rule or a rate<br/>could have changed in between, and one<br/>operation must not have two outcomes.
    H->>IE: publish PaymentAuthorized (again)
    Note over IE: ONE exit for both paths — nothing here<br/>distinguishes them, which is exactly what<br/>makes a LOST outcome event recoverable
    end

    rect rgb(255, 245, 245)
    Note over TX,IE: two concurrent first deliveries
    par delivery A
        H->>OPS: find(O1) → empty, decide, INSERT ✓
    and delivery B
        H->>OPS: find(O1) → empty, decide, INSERT ✗
        Note over OPS: primary-key violation — deliberately<br/>no ON CONFLICT anywhere against this table
        OPS--xH: rolls B's transaction back
        Note over H: B's retry then finds A's decision<br/>and republishes it
    end
    end
```

**"Decide once, announce every time"** is the whole rule, and the primary key is what enforces the
first half. It is load-bearing rather than merely tidy: the loser's constraint violation is what rolls
its transaction back so its retry can republish the winner's decision, instead of two deliveries
reaching two different answers.
