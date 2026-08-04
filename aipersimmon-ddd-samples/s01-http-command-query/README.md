# S1 — HTTP command and query, and the operation log (**S14**)

One HTTP write and one HTTP read, from the controller to a committed row: the shortest complete path
through `aipersimmon-ddd`, and the template the other samples inherit.

It also hosts **S14, the operation log** — here rather than in a larger sample because an audit row needs
nothing more than "somebody did something", and this is the shortest version of that sentence. The one
question the component cannot be wired without answering is *who* — `OperationActorResolver` has no default
and startup fails without it — so that is the question the `audit` package is about.

Companion documents: `docs/analysis/analysis-00015-samples-http-command-query.md` (S1) and
`docs/analysis/analysis-00038-samples-operation-log.md` (S14).

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

# and the audit trail of all of it. X-Actor stands in for an authenticated principal.
curl -isS localhost:18010/orders -H 'Content-Type: application/json' \
  -H 'X-Actor: clerk-7' -H 'X-Actor-Name: Dana Clerk' \
  -d '{"customerId":"customer-1","lines":[{"sku":"SKU-1","quantity":2}]}'
# then: SELECT operation_code, actor_type, actor_id, outcome, completion, summary
#         FROM aipersimmon_operation_log ORDER BY recorded_at;
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
| **S14** Who performed it, from a trusted scope | `audit/CurrentActor`, `audit/ActorBindingFilter`, `audit/AuditConfiguration` | `ActorResolutionTest` |
| **S14** Audit by annotation | `ordering/application/ConfirmOrder` | `OperationLogCaptureTest.theannotationPathTakesItFromTheInput` |
| **S14** Audit by type-safe Definition (a create's id is in the result) | `ordering/application/PlaceOrderAudit` | `OperationLogCaptureTest.thedefinitionPathTakesTheTargetIdFromTheResult` |
| **S14** Success in the business transaction, failure in its own | — | `AuditTransactionTest` |
| **S14** What a template may put in a row | — | `RestrictedTemplateTest` |

`mvn -pl s01-http-command-query -am verify` runs all 36 (15 for S1, 21 for S14). The HTTP test drives a real PostgreSQL
through Testcontainers and is guarded by `@EnabledIf(DockerAvailable)`, so it **skips** rather than
fails when Docker is absent — check for skips before trusting a green build on a new machine.

## Not demonstrated here, on purpose

| | Why |
| --- | --- |
| The `-starter-mybatis-plus` bundle | It brings the outbox, inbox and process-manager modules too, each registering a schema validator that refuses to start with no matching entry in `aipersimmon.ddd.flyway.components`. This sample owns exactly one framework table — the audit log — so it takes only the modules it uses. |
| Idempotency, rate limiting, replay protection | S2. `POST /orders` here is not retry-safe, deliberately: that is the next sample's subject. |
| Paging, filtering, cursors | S20. The only read here is by id. |
| The `ORDER_HAS_NO_LINES` invariant over HTTP | Unreachable from this endpoint, because the request DTO also rejects an empty list. That duplication is correct — the aggregate must not trust its callers — and which layer owns which check is S19. `OrderTest` covers the invariant directly. |
| A real identity provider | S14's actor comes from a request header standing in for an authenticated principal, and the README of that decision is in `audit/ActorBindingFilter`: a header is client-supplied and therefore the opposite of a trusted boundary. Swapping it for Spring Security's context changes nothing else. |
| Audit retention, and erasing personal data from an audit row | S27. `MybatisPlusOperationLogCleanup` exists and is opt-in; the conflict between a retention obligation and an erasure obligation is that sample's subject. |
| An audit row with before/after | A create has no before-state, so `changes` is unused here. S27 needs it, and needs it to be the thing an erasure has to deal with. |
| Domain event subscribers | `OrderPlaced` and `OrderConfirmed` are published on save and nothing listens yet. S3 picks them up. |
