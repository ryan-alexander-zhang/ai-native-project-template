# structure-1-modulith

Modular monolith with **logical** bounded contexts — Structure 1 of
[`decision-00004`](../../docs/decision/decision-00004-bounded-context-module-structure.md).
One deployable; each BC is a top-level package; boundaries are enforced at
**test time** by Spring Modulith + ArchUnit.

## Layout

```
com.acme.samples.s1
├── shared/                 published language (OrderPlaced, StockResult) + Money  [OPEN module]
├── ordering/               Bounded Context = Spring Modulith module
│   ├── domain/  application/  infrastructure/  web/       layers = sub-packages
└── inventory/              Bounded Context = Spring Modulith module
    ├── domain/  application/  infrastructure/
```

## What's distinctive here

- **BC boundary = package**, verified by `ApplicationModules.verify()`
  (`ModularityTests`). Moving a boundary is a package rename.
- **Transactional outbox = Spring Modulith event publication registry**
  (`spring-modulith-starter-jdbc`): `PlaceOrderService` publishes `OrderPlaced`
  in-process; `OrderPlacedRelay` (`@ApplicationModuleListener`) relays it to Kafka
  and the publication is tracked/retried by the registry.
- Config via `@ConfigurationProperties("samples")` (`SamplesProperties`).
- Note: Spring Modulith registers a Kafka `JsonMessageConverter`, so listeners
  take the typed event (`OrderPlaced` / `StockResult`), not a raw `String`.

## Run

```bash
make up && make s1     # from the parent dir; app on :8081
curl -X POST localhost:8081/orders -H 'Content-Type: application/json' \
  -d '{"customerId":"C1","lines":[{"sku":"BOOK-1","qty":2}]}'
```

`mvn verify` runs `ApplicationModules.verify()`.
