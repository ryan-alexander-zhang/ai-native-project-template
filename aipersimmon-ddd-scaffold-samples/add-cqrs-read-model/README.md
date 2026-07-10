# How-to: a CQRS command pipeline and read model

A focused, runnable example of the AiPersimmon DDD CQRS starter, and nothing else.

## What it shows

- **Command pipeline (`aipersimmon-ddd-cqrs-spring`)** — a `PlaceOrder`
  `Command` dispatched through the `CommandBus` runs its handler inside the
  built-in interceptor chain **logging → validation → transaction**. The handler
  (`PlaceOrderHandler`) stays thin: it persists the order and registers the
  aggregate with the `AggregateCollector`, opening no transaction and publishing
  nothing itself — the chain applies those concerns.

- **Read model from a domain event** — the transaction interceptor drains the
  `Order` aggregate's `OrderPlaced` event *within the command's transaction*. An
  `@Projection` (`OrderSummaryProjection`) listens for it and updates the
  `order_summary` read model, so the write-model row and the read-model row commit
  together.

- **Reading bypasses the write side** — a `FindOrderSummary` `Query` is answered by
  the `QueryBus` straight from the read-model table, never through the aggregate or
  the write repository.

## Run it

```
mvn test
```

The single test drives the pipeline against an in-memory H2 database, so no
external infrastructure is needed. It asserts: placing an order updates the read
model and is queryable; a blank command is rejected by validation before any
write; and a handler failure rolls back both the write model and the read model.
