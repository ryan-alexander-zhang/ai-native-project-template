# State diagrams — multi-module

Four state machines live in this codebase, and they are **deliberately separate**:

| Machine | States | Where it lives | Guarded by |
|---|---|---|---|
| [Order lifecycle](#1-order-lifecycle) | `OrderStatus`, 6 | `ordering.orders.status` | `Transitions` table + `OrderLifecyclePolicy` |
| [Fulfilment process](#2-fulfilment-process-manager) | `OrderFulfilmentState.Step`, 9 | process-manager row | `OrderFulfilmentDefinition` |
| [Process lifecycle](#3-process-lifecycle-the-runtimes-own) | `ProcessLifecycle`, 3 used | process-manager row | the library runtime |
| [Reservation](#4-reservation) | `released` flag, 2 | `inventory.reservations.released` | `Reservation.markReleased()` |

Keeping the first two apart is the point. An order's status is *this context's own conclusion about the
order*; a process step is *what the coordination is waiting for*. They advance at different moments and
one is not derivable from the other — which is exactly why `FULFILMENT_IN_PROGRESS` is a persisted
state rather than something a policy infers from the process manager's internal step.

---

## 1. Order lifecycle

```mermaid
stateDiagram-v2
    direction TB
    [*] --> AWAITING_REVIEW : Order.place<br/>review REQUIRED
    [*] --> READY_FOR_FULFILMENT : Order.place<br/>review NOT required

    AWAITING_REVIEW --> READY_FOR_FULFILMENT : approveReview(ReviewDecisionRef)<br/>[decision belongs to this order]
    AWAITING_REVIEW --> CANCELLED : cancel(ReviewRejected)<br/>[decision belongs to this order]
    AWAITING_REVIEW --> CANCELLED : cancel(CustomerRequested)<br/>[caller owns the order]

    READY_FOR_FULFILMENT --> FULFILMENT_IN_PROGRESS : beginFulfilment()<br/>dispatched by the process manager<br/>once a reservation EXISTS
    READY_FOR_FULFILMENT --> CANCELLED : cancel(CustomerRequested)<br/>[caller owns the order]
    READY_FOR_FULFILMENT --> CANCELLED : cancel(InventoryUnavailable)<br/>[ReservationFailureRef]

    FULFILMENT_IN_PROGRESS --> CONFIRMED : confirm()
    FULFILMENT_IN_PROGRESS --> CANCELLED : cancel(InventoryUnavailable)<br/>[ReservationFailureRef]
    FULFILMENT_IN_PROGRESS --> CANCELLED : cancel(PaymentDeclinedAfterStockReleased)<br/>[PaymentDeclineRef AND StockReleaseRef]

    CONFIRMED --> SHIPPED : ship()

    SHIPPED --> SHIPPED : cancel(any reason)<br/>REFUSED — ordering.return-required (409)

    CONFIRMED --> [*]
    SHIPPED --> [*]
    CANCELLED --> [*]

    note right of AWAITING_REVIEW
        Reserves nothing and starts no process.
        Only OrderPlacedEvent was registered —
        NOT OrderReadyForFulfilmentEvent.
    end note

    note right of READY_FOR_FULFILMENT
        The self-cancel window.
        CancellableByCustomer.BEFORE_FULFILMENT
        is satisfied here and in AWAITING_REVIEW,
        and nowhere else.
        A reservation has been ASKED for, not held.
    end note

    note right of FULFILMENT_IN_PROGRESS
        The pivotal state. The customer can no
        longer self-cancel; only the process
        manager, holding evidence of what
        inventory and payment did, may drive
        it to a terminal state.
    end note
```

### The two guards, and why there are two

```mermaid
stateDiagram-v2
    direction LR
    state "Transitions table<br/>(mechanical)" as T {
        [*] --> t1
        t1 : AWAITING_REVIEW → READY_FOR_FULFILMENT
        t2 : READY_FOR_FULFILMENT → FULFILMENT_IN_PROGRESS
        t3 : FULFILMENT_IN_PROGRESS → CONFIRMED
        t4 : CONFIRMED → SHIPPED
    }
    state "OrderLifecyclePolicy<br/>(evidence-dependent)" as P {
        [*] --> p1
        p1 : → CANCELLED, and only the policy decides
    }
```

`Transitions` covers the moves whose legality depends **only on the current state** — four of them,
all forward, all mechanical. Cancellation is not one of those: its legality depends on *why* and on
*proof*, which a flat table cannot express. So the aggregate asks the policy, the policy decides by
throwing or returning, and the aggregate stays the sole mutator.

### Cancellation, by reason

Only four reasons exist (the `sealed interface` guarantees it), and each is legal from a different set
of states:

| Reason | Legal from | Evidence required | Also checked | Published category |
|---|---|---|---|---|
| `CustomerRequested` | `AWAITING_REVIEW`, `READY_FOR_FULFILMENT` | — (the `CustomerId`) | caller **is** the order's customer | `CUSTOMER_REQUESTED` |
| `InventoryUnavailable` | `READY_FOR_FULFILMENT`, `FULFILMENT_IN_PROGRESS` | `ReservationFailureRef` | ref belongs to this order | `INVENTORY_UNAVAILABLE` |
| `PaymentDeclinedAfterStockReleased` | `FULFILMENT_IN_PROGRESS` | `PaymentDeclineRef` **and** `StockReleaseRef` | **both** refs belong to this order | `PAYMENT_DECLINED` |
| `ReviewRejected` | `AWAITING_REVIEW` | `ReviewDecisionRef` | ref belongs to this order | `REVIEW_REJECTED` |

And one rule that holds for **every** reason, checked before the policy branches at all:
`SHIPPED` refuses with `ordering.return-required`.

**`InventoryUnavailable` accepts `READY_FOR_FULFILMENT`, and that is now the ordinary case.** An
inventory failure means the reservation never succeeded — and since the order only advances to
`FULFILMENT_IN_PROGRESS` once it has, a failed or timed-out reservation finds the order still merely
*ready*. Requiring "under fulfilment" here would refuse the compensation for exactly the outcome
compensation exists for.

### What is never reachable, and why that is correct

- **`AWAITING_REVIEW → FULFILMENT_IN_PROGRESS`** — review must clear first. The table has no such
  entry, and `approveReview` additionally refuses any status but `AWAITING_REVIEW`.
- **`CANCELLED → anything`** — terminal. No transition leaves it.
- **`SHIPPED → CANCELLED`** — refused, not absent: the refusal carries a coded reason (a return flow is
  a different flow, and does not exist yet).
- **`READY_FOR_FULFILMENT → CONFIRMED`** — there is no `confirm` endpoint at all, so a client cannot
  skip fulfilment. `ConfirmOrder` reaches the aggregate only as a process-manager effect, and only from
  `FULFILMENT_IN_PROGRESS`.

---

## 2. Fulfilment process manager

Nine steps. Every transition is `(current step × input) → decision`, and every step also has an
**ignore** arm for facts that are duplicates or out of order.

```mermaid
stateDiagram-v2
    direction TB
    [*] --> AWAITING_STOCK : ReadyForFulfilment<br/>(start-only)<br/>+ arm STOCK (PT1M)

    AWAITING_STOCK --> AWAITING_PAYMENT : StockReserved<br/>▸ BeginFulfilment<br/>▸ RequestPayment<br/>▸ cancel STOCK<br/>▸ arm PAYMENT (PT2M)
    AWAITING_STOCK --> AWAITING_ORDER_CANCELLATION : StockReservationFailed<br/>▸ CancelOrder(InventoryUnavailable)<br/>▸ cancel STOCK
    AWAITING_STOCK --> AWAITING_ORDER_CANCELLATION : StockReservationTimedOut<br/>▸ CancelOrder(InventoryUnavailable,<br/>code STOCK_TIMEOUT)<br/>(no cancel — this IS the deadline)
    AWAITING_STOCK --> AWAITING_STOCK_ORDER_CANCELLED : OrderCancelled<br/>(STOCK stays armed)

    AWAITING_PAYMENT --> AWAITING_ORDER_CONFIRMATION : PaymentAuthorized<br/>▸ ConfirmOrder<br/>▸ cancel PAYMENT
    AWAITING_PAYMENT --> AWAITING_STOCK_RELEASE : PaymentDeclined<br/>▸ RequestStockRelease<br/>▸ cancel PAYMENT<br/>▸ arm STOCK_RELEASE (PT1M)
    AWAITING_PAYMENT --> AWAITING_STOCK_RELEASE : PaymentTimedOut<br/>▸ RequestStockRelease<br/>▸ arm STOCK_RELEASE<br/>(code PAYMENT_TIMEOUT)
    AWAITING_PAYMENT --> AWAITING_STOCK_RELEASE_ORDER_CANCELLED : OrderCancelled<br/>▸ RequestStockRelease<br/>▸ cancel PAYMENT<br/>▸ arm STOCK_RELEASE

    AWAITING_STOCK_RELEASE --> AWAITING_ORDER_CANCELLATION : StockReleased<br/>▸ CancelOrder(PaymentDeclined<br/>AfterStockReleased)<br/>▸ cancel STOCK_RELEASE
    AWAITING_STOCK_RELEASE --> AWAITING_STOCK_RELEASE : StockReleaseTimedOut<br/>▸ RequestStockRelease again<br/>▸ RE-ARM STOCK_RELEASE

    AWAITING_STOCK_ORDER_CANCELLED --> AWAITING_STOCK_RELEASE_ORDER_CANCELLED : StockReserved<br/>▸ RequestStockRelease<br/>▸ cancel STOCK<br/>▸ arm STOCK_RELEASE<br/>(no BeginFulfilment, no RequestPayment)
    AWAITING_STOCK_ORDER_CANCELLED --> CANCELLED : StockReservationFailed<br/>or StockReservationTimedOut<br/>(nothing to compensate)

    AWAITING_STOCK_RELEASE_ORDER_CANCELLED --> CANCELLED : StockReleased<br/>▸ cancel STOCK_RELEASE<br/>(no CancelOrder — already terminal)
    AWAITING_STOCK_RELEASE_ORDER_CANCELLED --> AWAITING_STOCK_RELEASE_ORDER_CANCELLED : StockReleaseTimedOut<br/>▸ RequestStockRelease again<br/>▸ RE-ARM STOCK_RELEASE

    AWAITING_ORDER_CONFIRMATION --> CONFIRMED : OrderConfirmed<br/>outcome ORDER_CONFIRMED
    AWAITING_ORDER_CANCELLATION --> CANCELLED : OrderCancelled<br/>outcome ORDER_CANCELLED

    CONFIRMED --> [*]
    CANCELLED --> [*]
```

### The three arms of every cell

`react` gates every fact on the current step, **not** on the input type alone. For each
`(step, input)` pair it does exactly one of three things:

| Arm | Behaviour | When |
|---|---|---|
| **advance / compensate / complete** | new step, new lifecycle, effects emitted | the step's expected fact |
| **ignore** | same lifecycle, same step, **no effects** | a duplicate, or a fact out of order for this step |
| **reject** | throws | only `ReadyForFulfilment`, which is start-only and structurally never reaches `react` |

**Why ignore rather than throw.** The runtime delivers at-least-once and treats a `react` throw as a
poison message it retries forever, so a stale or out-of-order fact *must not* throw. A type-only switch
had three concrete misbehaviours that this closes:

- `PaymentAuthorized` at `AWAITING_STOCK` used to confirm an order with nothing reserved;
- `PaymentDeclined` before a reservation used to release a `null` handle;
- `StockReleased` before a decline used to throw on a `null` decline code and wedge the queue.

`MaxLifetimeExceeded` — the runtime's own backstop input, not an `OrderFulfilmentInput` — is rejected
cleanly with `UnsupportedProcessInputException` so the runtime suspends the instance rather than
crashing on a `ClassCastException`. That backstop is deliberately **not armed** in this scaffold.

### The deadline table

```mermaid
stateDiagram-v2
    direction LR
    state "STOCK · PT1M" as S {
        [*] --> armed_s : start()
        armed_s --> [*] : cancelled on StockReserved,<br/>StockReservationFailed, or<br/>StockReserved-for-cancelled-order
        armed_s --> fired_s : StockReservationTimedOut
        fired_s --> [*] : cancel the order OUTRIGHT<br/>(nothing was reserved)
    }
    state "PAYMENT · PT2M" as P {
        [*] --> armed_p : StockReserved
        armed_p --> [*] : cancelled on PaymentAuthorized,<br/>PaymentDeclined, or OrderCancelled
        armed_p --> fired_p : PaymentTimedOut
        fired_p --> [*] : RELEASE the stock, THEN cancel<br/>(the decline's path, unchanged)
    }
    state "STOCK_RELEASE · PT1M" as R {
        [*] --> armed_r : RequestStockRelease
        armed_r --> [*] : cancelled on StockReleased
        armed_r --> fired_r : StockReleaseTimedOut
        fired_r --> armed_r : ask AGAIN and re-arm —<br/>this wait cannot be ENDED,<br/>only SATISFIED
    }
```

**Every step that waits on another context has a timer.** The test is not "will this context refuse
me?" but "**can this step's answer fail to arrive?**" — and for every `AWAITING_*` step that waits on a
broker, the answer is yes. Inventory *does* answer `StockReservationFailed`, but only for a business
failure; a technical one (an optimistic-lock conflict, a validation error, a database outage) throws out
of its handler and publishes nothing, and that silence is indistinguishable from the payment context's.

**The three are not symmetrical, because what a step can do about silence differs:**

| Step | Is stock held? | What the timeout does |
|---|---|---|
| `AWAITING_STOCK` | no | cancel outright — same branch as a refusal, different code |
| `AWAITING_PAYMENT` | **yes** | release, then cancel — the decline's path, unchanged |
| `AWAITING_STOCK_RELEASE` | **yes** | **cannot end the wait** — re-ask and re-arm |

The third is where the evidence-bearing `CancellationReason` earns its complexity. Cancelling from
there needs a `StockReleaseRef` proving the stock came back, and a timeout is precisely the *absence*
of that proof. A looser design would have "recovered" by declaring released stock that is still held.

**Deadlines are named, not identified.** Rescheduling the same name supersedes the previous generation
and cancelling it cancels only the current one, so a timer that fires just as the answer arrives cannot
resurrect a settled flow. Two branches leave a step *without* cancelling a deadline — the timeout
branches themselves — because that decision **is** the deadline firing.

### Which steps are terminal, and on what

The process reaches a terminal lifecycle only on the **actual outcome** — `OrderConfirmed` or
`OrderCancelled`, fed back in by `OrderFulfilmentStarter` from the aggregate's own domain events —
never when a confirm/cancel command is merely *dispatched*. That distinction is what makes the process
row a truthful record rather than an optimistic one.

Two steps complete without dispatching anything at all: both `*_ORDER_CANCELLED` exits, because the
order reached its terminal state through the customer's own cancellation and only the stock question
remained.

---

## 3. Process lifecycle (the runtime's own)

Orthogonal to the step. Every decision carries both.

```mermaid
stateDiagram-v2
    direction LR
    [*] --> RUNNING : start()
    RUNNING --> RUNNING : StockReserved, PaymentAuthorized
    RUNNING --> COMPENSATING : any failure or timeout,<br/>or the order being cancelled
    COMPENSATING --> COMPENSATING : StockReleased (mid-compensation),<br/>StockReleaseTimedOut
    RUNNING --> COMPLETED : OrderConfirmed<br/>outcome ORDER_CONFIRMED
    COMPENSATING --> COMPLETED : OrderCancelled, or a<br/>cancelled order's stock question closing<br/>outcome ORDER_CANCELLED
    COMPLETED --> [*]
```

| Step | Lifecycle |
|---|---|
| `AWAITING_STOCK`, `AWAITING_PAYMENT`, `AWAITING_ORDER_CONFIRMATION` | `RUNNING` |
| `AWAITING_STOCK_RELEASE`, `AWAITING_ORDER_CANCELLATION`, both `*_ORDER_CANCELLED` | `COMPENSATING` |
| `CONFIRMED`, `CANCELLED` | `COMPLETED` |

An **ignored** input keeps whatever lifecycle the instance currently has — `ignore` reads it from
`context.currentLifecycle()` rather than assuming one, which is what lets a duplicate arrive during
compensation without quietly moving the instance back to `RUNNING`.

A long-lived `COMPENSATING` instance is the visible symptom of the unbounded release retry, and is
what the process backlog metrics are for. `SUSPENDED` exists in the runtime and is reachable here only
by arming `instance.max-lifetime`, which this scaffold does not do.

---

## 4. Reservation

The smallest machine, and the one that makes retries safe.

```mermaid
stateDiagram-v2
    direction LR
    [*] --> HELD : new Reservation(id, orderId, heldBySku)<br/>[at least one line]
    HELD --> RELEASED : markReleased() → true<br/>▸ hand each quantity back to its Stock<br/>▸ publish StockReleased
    RELEASED --> RELEASED : markReleased() → false<br/>▸ NO hand-back<br/>▸ publish StockReleased ANYWAY
    note right of RELEASED
        The asymmetry is the whole point.
        The hand-back happens once, so stock
        cannot double. The announcement happens
        every time, so the process manager's
        wait for StockReleased always resolves.
    end note
```

And `Stock` itself, per SKU — no status column, just a quantity with two guarded operations:

```mermaid
stateDiagram-v2
    direction LR
    [*] --> available
    available --> available : reserve(q)<br/>[q > 0 AND q <= available]<br/>available -= q
    available --> available : release(q)<br/>[q > 0]<br/>available += q
    available --> refused : reserve(q)<br/>[q > available]<br/>DomainException(inventory.insufficient-stock)
    refused --> available : nothing was written —<br/>the whole reservation fails,<br/>all lines or none
```

**`Stock` is one aggregate root per SKU**, which is the natural contention boundary: forcing all SKUs
into one aggregate would serialise unrelated stock. Reserving a multi-line order therefore mutates
several roots plus one `Reservation` in a single application transaction — a deliberate multi-aggregate
transaction where "all lines or none" is held by the transaction rather than by an aggregate.

**Why the refusal never leaves a partial deduction.** `ReserveStockHandler` splits *decide* from
*write*: every load and every domain decision happens first, against in-memory `Stock` objects that
have already absorbed this command's earlier lines, and nothing is saved until no decision is left to
make. So a shortfall throws with nothing written, and there is nothing to undo.

---

## The four machines, side by side

One order, one row in each. This is what the separation buys — you can read any one of them without
the others, and no state is inferred:

```mermaid
stateDiagram-v2
    direction TB
    state "ordering.orders.status" as A {
        a : READY_FOR_FULFILMENT → FULFILMENT_IN_PROGRESS → CONFIRMED
    }
    state "process step" as B {
        b : AWAITING_STOCK → AWAITING_PAYMENT → AWAITING_ORDER_CONFIRMATION → CONFIRMED
    }
    state "process lifecycle" as C {
        c : RUNNING → COMPLETED
    }
    state "inventory.reservations.released" as D {
        d : HELD (stays held on the happy path)
    }
```

On the happy path the reservation is never released — the stock is genuinely sold. `released` becomes
`true` only on a compensation branch, which is why it is the flag the compensation's idempotency hangs
off.
