# S1 — HTTP command and query

One HTTP write and one HTTP read, from the controller to a committed row: the shortest complete path
through `aipersimmon-ddd`, and the template the other samples inherit.

Companion document: `docs/analysis/analysis-00015-samples-http-command-query.md`.

## Run it

```bash
docker compose up -d
mvn -pl s01-http-command-query -am spring-boot:run     # from aipersimmon-ddd-samples/
```

```bash
# place an order -> 201, the resource itself, no envelope
curl -isS localhost:18010/orders \
  -H 'Content-Type: application/json' \
  -d '{"customerId":"customer-1","lines":[{"sku":"SKU-1","quantity":2}]}'

ID=<the id from the response>

curl -sS localhost:18010/orders/$ID                      # 200
curl -isS -X POST localhost:18010/orders/$ID/confirm     # 204
curl -isS -X POST localhost:18010/orders/$ID/confirm     # 409, /problems/order-not-confirmable
curl -sS localhost:18010/orders/nope                     # 404, /problems/resource-not-found
```

API docs at `localhost:18010/swagger-ui.html`.

## Code tour

| Concern | Where | Verified by |
| --- | --- | --- |
| Controller translates and nothing else | `ordering/interfaces/OrderController` | `OrderHttpContractTest` |
| Command with its own constraints | `ordering/application/PlaceOrder` | `OrderHttpContractTest.anInvalidBodyIsRejectedBeforeAnyCommandIsSent` |
| Handler as a concrete class, no `@Transactional` | `ordering/application/*Handler` | `ConfirmOrderHandlerTest` |
| Invariant refuses, transition table refuses | `ordering/domain/OrderHasLines`, `Order.TRANSITIONS` | `OrderTest` |
| Version-checked write + event publication | `ordering/infrastructure/MyBatisOrders` | `OrderHttpContractTest` |
| Error codes per context, category drives the family | `ordering/domain/OrderingErrorCode` | `OrderHttpContractTest.anUnknownOrderRidesItsCategoryFamily` |
| One problem-type override | `ordering/interfaces/OrderingProblemConfig` | `OrderHttpContractTest.confirmingTwiceIsTheContextsOwnProblemType` |
| Layering and building-block rules | — | `ArchitectureTest`, `PackageInfoTest` |

`mvn -pl s01-http-command-query -am verify` runs all 15. The HTTP test drives a real PostgreSQL
through Testcontainers and is guarded by `@EnabledIf(DockerAvailable)`, so it **skips** rather than
fails when Docker is absent — check for skips before trusting a green build on a new machine.

## Not demonstrated here, on purpose

| | Why |
| --- | --- |
| The `-starter-mybatis-plus` bundle | It brings the outbox, inbox, process-manager and operation-log modules, each registering a schema validator that refuses to start with no `aipersimmon.ddd.flyway.components` set. This sample owns no framework table, so it takes only the three modules it uses. |
| Idempotency, rate limiting, replay protection | S2. `POST /orders` here is not retry-safe, deliberately: that is the next sample's subject. |
| Paging, filtering, cursors | S20. The only read here is by id. |
| The `ORDER_HAS_NO_LINES` invariant over HTTP | Unreachable from this endpoint, because the request DTO also rejects an empty list. That duplication is correct — the aggregate must not trust its callers — and which layer owns which check is S19. `OrderTest` covers the invariant directly. |
| Auditing, and the operator identity it needs | There is no operation log here, so nothing needs to know who performed the request. S14 brings both, together. |
| Domain event subscribers | `OrderPlaced` and `OrderConfirmed` are published on save and nothing listens yet. S3 picks them up. |
