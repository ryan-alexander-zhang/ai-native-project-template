# bc-and-layer-samples

Three runnable Spring Boot projects, one per structure in
[`decision-00004`](../docs/decision/decision-00004-bounded-context-module-structure.md),
implementing the **same** `Ordering` + `Inventory` domain and scenario so the
structures can be compared directly.

| Dir | Structure | Deployables | BC boundary | Layer boundary | Port |
| --- | --- | --- | --- | --- | --- |
| `structure-1-modulith/` | modular monolith, logical BCs (Spring Modulith) | 1 | package, `verify()` | sub-package, ArchUnit | 8081 |
| `structure-2-multimodule/` | modular monolith, physical BCs (multi-module) | 1 | Maven module | Maven module | 8082 |
| `structure-3-microservices/` | service per BC (COLA) | 2 | separate service | Maven module | 8083 / 8084 |

## Middleware (real, via Docker Compose)

Postgres 18.1, Kafka 3.7.1 (KRaft), WireMock (external pricing stub). Apps run on
the host and connect over localhost.

```bash
make up            # start middleware, wait until ready
make s1            # build + run structure-1 (repeat with s2 / s3-ordering + s3-inventory)
make reset         # stop middleware and wipe volumes (clean slate)
make help          # all targets
```

Run **one structure at a time**; each uses its own DB schemas
(`sN_ordering` / `sN_inventory`) and Kafka topic/group prefix (`sN.`), so they
never share state. See [`plan-00001`](../docs/plan/plan-00001-bc-and-layer-samples.md)
for the full design and acceptance path.

> Per-structure details and the "what to look at" guide are filled in once each
> project is implemented (plan task T5).
