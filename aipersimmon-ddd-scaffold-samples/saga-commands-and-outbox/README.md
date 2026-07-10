# How-to: a saga that issues commands, with reliable outbound via the outbox

A focused, runnable example combining the CQRS command bus, a saga, and the
transactional outbox — and nothing else.

## What it shows

This is orchestration in its fuller form: the process manager doesn't just react
to events, it **drives the flow by sending commands**, and each command reliably
**announces its outcome through the outbox**.

- **The saga sends commands (`aipersimmon-ddd-cqrs-spring` + `aipersimmon-ddd-saga`)** —
  `OrderFulfilment` (a `@ProcessManager`) advances the flow by dispatching
  `ReserveStock`, then `ConfirmOrder` (or `CancelOrder`) through the `CommandBus`.
  It never calls a handler or writes state directly; the bus applies the
  transaction boundary around each command.

- **Reliable outbound (`aipersimmon-ddd-outbox-jdbc`)** — each command handler
  publishes its integration event (`StockReserved`, `StockReservationFailed`,
  `OrderConfirmed`) through the `IntegrationEvents` port, which writes an outbox row
  *in the command's transaction*. The state change and the event commit or roll
  back together — no dual-write.

- **The loop closes through the relay** — with in-process dispatch
  (`aipersimmon.ddd.outbox.dispatch=in-process`) the relay redelivers those stored
  events to the saga's `@EventListener`s, which send the next command. Delivery is
  at-least-once, so a reaction may arrive twice; the saga's guarded lifecycle makes
  that a no-op.

Flow: place order → saga starts, sends `ReserveStock` → handler reserves + emits
`StockReserved` via the outbox → relay redelivers → saga sends `ConfirmOrder` →
handler sets the order CONFIRMED (and emits `OrderConfirmed`).

## Run it

```
mvn test
```

Three tests run against an in-memory H2 database, so no external infrastructure is
needed: the happy path (reserve → confirm), compensation (reserve fails → cancel),
and reliable outbound (a handler failure rolls back both the write and the outbox
row, so nothing is emitted).
