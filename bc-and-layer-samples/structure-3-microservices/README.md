# structure-3-microservices

**Service per bounded context** (COLA-style) — Structure 3 of
[`decision-00004`](../../docs/decision/decision-00004-bounded-context-module-structure.md).
Two **independent** Spring Boot services, each its own deployable and its own
schema, communicating over the network. They share **no code** — each carries
its own copy of the wire contract, as real microservices do. (The aggregator
`pom.xml` only builds both together for convenience.)

## Layout

```
structure-3-microservices/
├── ordering-service/    :8083  own repo/deployable, schema s3_ordering
│   └── com.acme.samples.s3.ordering.{client,domain,app,infrastructure,adapter}
└── inventory-service/   :8084  own repo/deployable, schema s3_inventory
    └── com.acme.samples.s3.inventory.{client,domain,app,infrastructure,adapter}
```

Each service is COLA-layered by package. In production each layer would be its
own Maven module (see structure-2 for that mechanic); here each service is a
single module to keep it runnable — the point of this structure is the
**deployment boundary**, not internal layering.

## What's distinctive here

- **Two deployables**, network boundary between contexts.
- **Synchronous cross-service REST**: `ordering-service` calls
  `inventory-service` `GET /availability` (`InventoryClient`) as a pre-check
  before placing — demonstrating sync coupling and its failure mode (if
  inventory is down, placing fails).
- **Async cross-service Kafka**: ordering's own transactional outbox →
  `s3.order-placed` → inventory reserves → `s3.stock-result` → ordering confirms.
- The `*-api` contract + integration events are the stable interface across the
  promotion path (package BC → module BC → service). Same contract as
  structures 1 and 2; only the transport changed.

## Run (start inventory first — ordering calls it synchronously)

```bash
make up
make s3-inventory      # :8084
# in another shell:
make s3-ordering       # :8083
curl -X POST localhost:8083/orders -H 'Content-Type: application/json' \
  -d '{"customerId":"C1","lines":[{"sku":"BOOK-1","qty":2}]}'
```
