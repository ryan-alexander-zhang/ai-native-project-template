# How-to: orchestrate a flow with a saga

A focused, runnable example of the AiPersimmon DDD saga starter, and nothing else.

## What it shows

- **A process manager (`aipersimmon-ddd-saga` / `-saga-spring`)** — `OrderFulfilment`
  coordinates one order-fulfilment flow from a single place: it starts the saga when
  an order is placed, arms a confirmation **deadline**, completes the saga when stock
  is reserved, and — if the deadline fires first — **compensates and ends** it. This
  is orchestration: the flow's state and branches live in one state machine, rather
  than being spread across independent event handlers (choreography).

- **Deadlines** — the confirmation timeout is registered with the `DeadlineScheduler`
  and cancelled when stock is reserved in time. The saga starter's in-process
  scheduler fires it and calls back `OrderFulfilment` (a `DeadlineHandler`).

- **Persisted saga state with optimistic locking** — `OrderFulfilmentSaga` (state +
  guarded lifecycle) is stored by `OrderFulfilmentSagaStore` (a `JdbcSagaStore`
  subclass), keyed by the order id.

## Run it

```
mvn test
```

Two tests run against an in-memory H2 database, so no external infrastructure is
needed. `SagaOrchestrationHowToTest` drives the branches deterministically
(completing on reservation, compensating on timeout, ignoring a late deadline).
`SagaDeadlineFiresHowToTest` uses a short timeout to prove the scheduler fires the
deadline and compensates on its own.
