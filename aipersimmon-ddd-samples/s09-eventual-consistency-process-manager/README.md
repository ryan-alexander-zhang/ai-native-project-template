# S9 — Three writes, no transaction that can hold them, and a compensation that is not a rollback

Selling one ticket touches a seat counter, a customer's balance and the order. Nothing can make those
three atomic, and no ordering makes failure impossible — so the flow is explicit, durable, and reversible
by compensation.

Companion document: `docs/analysis/analysis-00032-samples-eventual-consistency-process-manager.md`.

## Run it

```bash
mvn -pl s09-eventual-consistency-process-manager -am verify    # 24 tests, from aipersimmon-ddd-samples/
```

They need Docker (PostgreSQL) and **skip** rather than fail without it. By hand:

```bash
mvn -pl s09-eventual-consistency-process-manager -am spring-boot:run

curl -sS -X POST localhost:18090/orders -H 'Content-Type: application/json' \
     -d '{"customerId":"customer-1","seatClass":"STALLS","amountMinor":4500}'
curl -sS localhost:18090/orders/<id>          # the truth: PLACED / TICKETED / CANCELLED
curl -sS localhost:18090/flows/<id>           # the coordination: lifecycle, step, suspension reason

# the compensating path: BALCONY has no seats
curl -sS -X POST localhost:18090/orders -H 'Content-Type: application/json' \
     -d '{"customerId":"customer-1","seatClass":"BALCONY","amountMinor":4500}'
```

## The flow

```
                 seat ──────────► money ──────────► ticket        (forward)
                  │                 │                 │
      sold out /  │      declined / │                 │ issued → COMPLETED "ticketed"
      timed out   │      timed out  │                 │
                  ▼                 ▼                 ▼
           cancel order ◄── release seat ◄── refund wallet         (compensating, in reverse)
                  │
                  ▼
            COMPLETED "cancelled"
```

Six steps, two deadlines, twelve inputs, one cancellation request that can arrive at any of them.

## Compensation is not a rollback, and the ledger proves it

| What was done | What makes it good | Why it is not an undo |
| --- | --- | --- |
| The wallet was debited | A **credit**, referencing the debit | Both entries stay on the statement forever |
| A seat was held | The hold is marked `released_at` | The row is kept, with the time it was let go |
| The order was placed | The order is cancelled, with a reason | A cancelled order is a fact, not an absence |

A test asserts the statement after a mid-flow cancellation:

```
DEBIT  4500  ticket-debit:<orderId>
CREDIT 4500  refund-of:ticket-debit:<orderId>
```

Balance back where it started, two entries rather than none. A rollback would have left one entry that
never existed — and a refund in the real world can carry a fee, take days, or be refused, which is the
general reason this distinction is not pedantry.

**And there is a point of no return.** After the ticket is issued the flow is over: a later cancellation is
absorbed, because undoing a ticket is a refund flow with its own authorisation. The aggregate agrees loudly
(`TicketOrder.cancel` throws once ticketed) — and the library agrees more loudly still: the runtime
short-circuits a terminal instance before consulting the definition at all
(`DefaultProcessRuntime:510`), so the flow cannot restart past its own ending even if its code wanted to.

## Who holds the truth

The question the catalogue calls the most-skipped one, answered in code and asserted structurally.

| | Holds | Vocabulary |
| --- | --- | --- |
| `TicketOrder.status` | **the truth** — what is true of the order | PLACED / TICKETED / CANCELLED |
| `TicketingState.Step` | what the coordinator is waiting for | AWAITING_SEAT / AWAITING_PAYMENT / … |

They **disagree by design** during every step: the flow reaches `AWAITING_TICKET` while the order is still
`PLACED`, because the command has been dispatched and not yet committed. A test asserts that window and
calls it correct.

What would be a defect is the flow holding a copy of `status`, so a test reflects over
`TicketingState.getRecordComponents()` and asserts there is no `OrderStatus` component and nothing named
`status`. The rule that generalises, and the one to take away:

> **A flow may remember facts. It may never remember conclusions.**

`customerId`, `seatClass` and `amountMinor` are facts fixed when the instance started — nothing can change
them behind its back, and every compensating command needs them. `status` is a conclusion that changes
independently. The test to apply is exactly that: could this value change after the flow copied it?

## The definition is pure, and that is the whole deal

No repository, no bus, no clock, no HTTP — the library requires it, and an ArchUnit rule here enforces it
because nothing at runtime does. What it buys: `TicketingDefinitionTest` runs seven cases with no database
and no Spring, in milliseconds, and can put the flow in any state. What it costs: the definition **cannot
cheat**. Anything a later step needs has to be carried in the state, because there is nothing to look it up
from.

## Two layers of idempotency, and why there is no inbox

The runtime is at-least-once, so both are load-bearing rather than defensive:

1. **The participants.** `SeatClass.hold` recognises an existing hold (`ALREADY_HELD`); `Wallet.debit`
   recognises a reference (`ALREADY_APPLIED`). Every operation returns an **outcome** instead of throwing,
   because "sold out" and "insufficient funds" are answers the coordinator must compensate for — thrown,
   they would only reach the relay, which would retry a request that fails identically forever.
2. **The runtime.** Each command effect is delivered under the effect's own persisted message id
   (`CommandBus.sendAs`), and the handler hands that same `CommandContext` back as the cause. A redelivery
   therefore produces a byte-identical input message id and the runtime returns the original transition. A
   test asserts a duplicated `SeatHeld` stages one charge, not two.

**So this sample needs no inbox.** An inbox deduplicates messages arriving from *outside* (S4's subject);
these arrive from the coordinator's own effect table, which already gave each one an identity. Same idea,
two different boundaries.

## Codecs: nineteen lines of registration, and why they cannot be inferred

**A class name is not a persistence contract.** Each entry names a stable logical type and version; the
Java class is the current carrier. A flow instance easily outlives three refactorings — it is a row waiting
for something that has not happened yet — so reflecting over class names would make every rename a silent
data migration.

**And a forgotten registration must not be found at the worst moment.** The definition declares its payload
classes and the startup validator reconciles them against the catalog. Measured both ways:

| | Result |
| --- | --- |
| One entry removed, declaration kept | **Startup fails**, naming `TicketingInput$WalletRefunded` |
| One entry removed, declaration emptied | **Starts happily**; 23 of 24 tests pass; the only red one is the flow that reaches the compensating branch |

Without the declaration, the breakage hides in the path you exercise least.

## When a flow is stuck

`StuckFlowTest` runs the whole story: an order for a seat class that does not exist → the participant throws
→ with `max-attempts=1` the effect goes DEAD → the instance goes SUSPENDED → the data is fixed →
`redriveEffect` → the instance resumes → the flow completes and the ticket is issued. **The order did not
have to be replaced or reconciled by hand**, which is the practical advantage of a durable coordinator over
a chain of listeners.

Three things worth knowing before you need them:

- The library offers exactly three operator actions — redrive an effect, redrive a deadline, cancel an
  instance — and **no `setState` or `forceStep`**. A coordinator whose state can be hand-edited has
  whatever invariants the last operator believed.
- `redriveEffect` accepts **only DEAD effects**. It is not a way to replay a delivered one.
- **Diagnosis takes two rows.** The instance says which work is stuck (`effect … exhausted retries`); the
  effect's `last_error` says why. No port exposes the second one — filed as `issue-00164`.

Also filed from this sample: `issue-00163` — `effect-relay.enabled=false` removes the relay *bean*, where
the outbox's identically-shaped `relay.enabled=false` keeps it and stops only the schedule. That is why the
tests here silence the workers with a one-hour poll delay instead.

## Code tour

| Concern | Where | Verified by |
| --- | --- | --- |
| The flow, readable top to bottom | `TicketingDefinition` | `TicketingDefinitionTest` (7, no DB) |
| Order row and flow row in one transaction | `PlaceTicketOrderHandler` | `thehappyPathTicketsTheOrder…` |
| Compensation in reverse order | the compensating steps | `acancellationAfterTheChargeRefunds…` |
| A refund is a new entry | `Wallet.credit` + `s09_wallet_entry` | the ledger assertion |
| A release keeps its history | `SeatClass.release` | `areleasedSeatKeepsItsHoldRow…` |
| The point of no return | `TicketOrder.issueTicket` / `cancel` | `acancellationAfterTheTicketIsIssued…` |
| Who holds the truth | `TicketingState` vs `TicketOrder` | two tests, one structural |
| A deferred cancellation | `cancellationRequested` in the state | unit + integration |
| Timeouts as ordinary inputs | `ScheduleDeadline` → `SeatWaitTimedOut` | `astepThatIsDeliveredAndNeverAnswered…` |
| Both idempotency layers | outcomes + effect identity | two tests |
| Startup-time codec reconciliation | `declaredPayloads` + `TicketingCodecs` | measured, both ways |
| Stuck, suspended, redriven | `StuckFlowTest` | 1 test, the whole arc |
| Purity, and the domain's ignorance | ArchUnit | `ArchitectureTest` (3 rules) |
| Both workers wired | `UnattendedFlowTest` | 1 test |

## Five negative controls, each run on its own

| Change | Red |
| --- | --- |
| A codec entry removed, declaration kept | startup fails (all tests) |
| Same, declaration emptied | exactly 1 — the compensating branch |
| `ALREADY_HELD` check removed | exactly 1 — one order, two seats |
| Compensation order swapped | exactly 1 — and the refund never happens at all |
| `ignored()` replaced by a throw | 2 — a duplicate fact breaks the flow |

## Not demonstrated here

| | |
| --- | --- |
| `PublishIntegrationEvent` effects | The participants are in one process, so `DispatchCommand` is right. Across services you swap one effect kind for the other and the definition is otherwise unchanged — but that needs an outbox and a broker, which is S4. |
| Two definition versions coexisting | The library supports it (`definitionVersion` + `activeForNewInstances`); this flow has one version. |
| State schema upgrades | Same: `StateSchemaVersion` plus a new codec, documented rather than shown. |
| Fan-out and join | Deliberately: when the state starts holding counters and to-do lists, the flow has outgrown this engine. The companion names the boundary and the direction (Temporal). |
| Retention of transitions | `cleanup.enabled` is off here. A forever-growing transition table is a real cost and a topic of its own. |
