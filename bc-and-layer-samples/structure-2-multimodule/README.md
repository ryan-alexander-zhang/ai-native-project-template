# structure-2-multimodule

Modular monolith with **physical** bounded contexts — Structure 2 of
[`decision-00004`](../../docs/decision/decision-00004-bounded-context-module-structure.md).
One deployable (`start`), but every bounded context *and* every layer is its own
Maven module, so boundaries are enforced at **compile time**.

## Layout (12 modules + parent)

```
structure-2-multimodule/            reactor POM
├── shared-kernel/
├── ordering/  ordering-{api,domain,application,infrastructure,adapter}
├── inventory/ inventory-{api,domain,application,infrastructure,adapter}
└── start/                          the only @SpringBootApplication
```

## What's distinctive here

- **Compile-time isolation both axes.** `*-domain` has no Spring/JPA on its
  classpath (framework leak won't compile); a context depends only on another
  context's `*-api` (cross-BC leak won't compile).
- **Package-per-aggregate with package-private internals** (decision-00005):
  `ordering-domain` is split into `order/` and `customer/` packages; `OrderLine`
  is package-private, so outside the `order` package you cannot `new OrderLine(...)`
  — the "reach an aggregate only through its root" rule is compiler-enforced. The
  root is built/rehydrated from public `OrderLineData`, so the repository (another
  module) never touches the internal entity.
- **Layers grouped by their nature** (per decision-00005): domain and application
  by aggregate (`application/order`); the *technical* layers by concern —
  `infrastructure/persistence/{order,customer}` (persistence sub-grouped by
  aggregate), `infrastructure/messaging` (outbox / inbox), `infrastructure/external`
  (gateway clients); inbound adapters by mechanism (`adapter/web`,
  `adapter/messaging`).
- **`*-api`** modules hold the published language (integration events); cross-BC
  references target only these.
- **Hand-rolled transactional outbox**: `OutboxWriter` inserts into the `outbox`
  table in the same tx as the order; `OutboxRelay` (`@Scheduled`) publishes to
  Kafka and marks rows sent.
- **`ArchitectureTests`** (ArchUnit) backstops the rules the module graph already
  enforces (`resolveMissingDependenciesFromClassPath=false` keeps it fast/robust
  on newer JDKs).
- `@MapperScan(annotationClass = Mapper.class)` so only `@Mapper` interfaces are
  registered (domain ports are not).

## Run

```bash
make up && make s2     # from the parent dir; app on :8082
```

`mvn verify` compiles all modules and runs the ArchUnit boundary tests.
