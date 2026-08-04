# S10 — Two services, two databases, one outcome

Spend money in one service, earn points in another, and require that both happen or neither does — with the
customer waiting for the answer. This is the sample where the library does the least and the deployment does
the most.

Companion document: `docs/analysis/analysis-00033-samples-strong-consistency-seata.md`.

## Run it

```bash
mvn -pl s10-strong-consistency-seata/distributed-transaction-tests -am verify   # 29 tests
```

The tests need Docker (two PostgreSQL containers and the real `apache/seata-server` image) and **skip**
rather than fail without it. By hand:

```bash
cd s10-strong-consistency-seata && docker compose up -d
mvn -pl s10-strong-consistency-seata/points-service  -am spring-boot:run    # :18101
mvn -pl s10-strong-consistency-seata/account-service -am spring-boot:run    # :18100

# both halves commit
curl -sS -X POST localhost:18100/purchases/at -H 'Content-Type: application/json' \
     -H 'X-Tenant-Id: acme' \
     -d '{"reference":"buy-1","accountId":"customer-1","amountMinor":2500,"points":25}'
curl -sS localhost:18100/accounts/customer-1 -H 'X-Tenant-Id: acme'
curl -sS localhost:18101/points/customer-1   -H 'X-Tenant-Id: acme'

# neither half commits: thenFail throws after both have already written
curl -sS -X POST localhost:18100/purchases/at -H 'Content-Type: application/json' \
     -H 'X-Tenant-Id: acme' \
     -d '{"reference":"buy-2","accountId":"customer-1","amountMinor":2500,"points":25,"thenFail":true}'

# the same use case over TCC instead
curl -sS -X POST localhost:18100/purchases/tcc -H 'Content-Type: application/json' \
     -H 'X-Tenant-Id: acme' \
     -d '{"reference":"buy-3","accountId":"customer-1","amountMinor":2500,"points":25}'
```

Seata's console is on <http://localhost:7091> and shows global transactions and their locks.

## The blocking question, answered first

The scenario catalogue would not let this sample be written until one thing was verified: **Seata AT builds
its rollback by parsing the SQL it intercepts, and by the time it sees this library's writes they have already
been rewritten twice** — by the optimistic locker (`SET version = version + 1 ... WHERE version = ?`) and by
the tenant line (`AND tenant_id = ?`). Two layers of SQL rewriting, and nobody had checked whether the
rollback still comes out right.

**It does.** Read off the undo log itself rather than inferred:

| What the undo log shows | Why it matters |
| --- | --- |
| `"name":"version","keyType":"NULL"` in both images, before `1` and after `2` | `version` is captured as an ordinary column, so a rollback restores it. Had it been left at 2, the balance would be right and the row permanently unwritable by anyone holding a version-1 snapshot. |
| `"name":"tenant_id","keyType":"PRIMARY_KEY"` and `"name":"id","keyType":"PRIMARY_KEY"` | Seata took the composite key from table metadata, so the tenant column is part of what it rolls back by — it did not have to infer it from the rewritten predicate. |
| `"name":"last_note",...,"value":null` in the after-image | The framework's cleared-column handling forces an explicit `SET last_note = NULL`; Seata's parser captures it rather than dropping it. |
| lock key `s10_account:acme_customer-1` | The global lock is taken on the whole primary key, tenant column included. |

The reason it works is layering, and it is worth saying explicitly: **the interceptors rewrite SQL above the
DataSource, and Seata's proxy is the DataSource.** By the time Seata parses anything, the rewriting is
finished and it is looking at ordinary final SQL. Nothing in the library needed to change, and nothing in the
library knows Seata exists.

So AT is this sample's main line. TCC is here as the measured alternative rather than as a fallback.

## undo_log is not yours to migrate

The first thing that goes wrong, and it goes wrong at startup:

```
java.lang.IllegalStateException: in AT mode, undo_log table not exist
```

Seata checks for the table inside `DataSourceProxy.init` — unconditionally, no property defers it — which runs
while the **DataSource bean is being constructed**. Flyway, `spring.sql.init` and the framework's own flyway
components all run after that, because they all depend on the DataSource. There is no ordering that lets an
application create this table for itself.

So `undo_log` belongs with the database: this sample creates it from `docker/undo_log.sql` in the container's
init hook, and the tests create it with `withInitScript`. Two knock-on effects, both configured up front
rather than discovered later:

- the schema is no longer empty when Flyway first runs, so Flyway needs `baseline-on-migrate: true`;
- and `baseline-version` must be `0`, or Flyway baselines *at* 1 and skips `V1` as already applied — which
  fails much later and looks like a missing table rather than a migration problem.

## Where the boundary goes

```
HTTP  →  @GlobalTransactional (application service)  →  @Transactional (command bus)  →  aggregate
```

One layer above the command bus, and the ordering is not stylistic: a branch **is** one committed local
transaction plus its undo log, so the local transaction has to begin and end inside the global one. Below it
there is nothing to undo. On the controller, the boundary of a business decision would be defined by a URL —
and the next entry point (a job, a consumer, an admin tool) would silently get no transaction at all. An
ArchUnit rule holds that line.

Two adjacent annotations with opposite defaults, worth knowing before relying on either: Spring's
`@Transactional` rolls back on unchecked exceptions only; Seata's `@GlobalTransactional` rolls back on any
`Throwable`.

## What "strong consistency" actually means here

Not "no intermediate state exists". The debit's local transaction really commits, and a plain reader sees the
reduced balance and the awarded points while the global transaction is still undecided — asserted mid-flight
inside `atholdsThePointsRowForTheWholeTransactionAndTccDoesNot`, four seconds before that transaction decides
anything.

What it means is **no intermediate state is reachable by another global transaction**, because the global lock
refuses one. That is the guarantee, and it is also the bill:

| Measured | AT | TCC |
| --- | --- | --- |
| A second global transaction wanting the same row | Refused, then `Global lock wait timeout` | Goes through |
| How long the row is held | The whole business transaction, including the remote call and its timeout | Until Try commits |
| Model changes needed | None | A `frozen` column and three methods instead of one |

Both rows come from one test, `atholdsThePointsRowForTheWholeTransactionAndTccDoesNot`, which keeps a
transaction open for four seconds and has a second one try to get in. The refusal is shown to be contention
rather than rejection: the identical request succeeds once the lock is gone.

The lock the second transaction could not have, taken from the service's log during that test:

```
get global lock fail, lockKeys:s10_points_account:acme_shared-loyalty;s10_points_entry:acme_contend-at
```

Two things in that one line. The key is `table:tenant_id_id` — **the whole composite primary key**, so tenancy
is inside the lock and two tenants never contend. And there are *two* keys: the aggregate root's row and the
child row it wrote. An aggregate's children are locked with it, which is right, and which means the lock
footprint of a write grows with how much of the aggregate it touches.

One consequence for library users specifically: that refusal is a `QueryTimeoutException`, **not** an
`OptimisticLockingFailureException`, so `aipersimmon.ddd.cqrs.retry-on-conflict` does not recognise it and
will not retry it. It is off in this sample for that reason — the contention moved somewhere the interceptor
cannot see, and leaving the retry on would add retries that never fire while suggesting the case is covered.

## AT or TCC, in one paragraph you can apply

TCC's three methods are not plumbing bolted onto an aggregate. They are **the aggregate admitting that
"reserved" is part of its language.** `PointsAccount` gains `frozen` because promised points are a real fact
about points — a thing the business can see, report on and reason about. `Account` gains nothing, because a
balance is a balance and "debited but not yet certain" is not a state anyone outside the transaction manager
wants to talk about.

So the test is not throughput and it is not elegance: **does the business already have a word for the
intermediate state?** If yes, that state was missing from your model and TCC merely forces the issue. If no,
TCC is being used to fake one, and AT — which keeps the intermediate state in a lock instead of in the model —
is the honest choice. Contention is the tiebreaker, not the question.

## The three TCC hazards, in the model rather than in a framework

| Hazard | What it looks like | Where it is handled |
| --- | --- | --- |
| **Idempotency** | Seata retries Confirm and Cancel until one succeeds | Every method keyed on the reference, returning an outcome |
| **Empty rollback** | Cancel for a Try that never ran | `cancelReservation` tolerates it — and still writes a mark |
| **Suspension** | Try arrives *after* its own Cancel | `reserve` refuses a cancelled reference; without the mark above it could not |

The mark is the part that gets dropped. Without it a late Try freezes points that no Confirm and no Cancel is
ever coming for, and nothing anywhere reports a problem.

## The XID asymmetry, and two guards

AT propagates `TX_XID` to the participant. **TCC deliberately does not** — a TCC branch is registered on the
caller's side, and its phases are ordinary local transactions on the participant's side. Propagate the XID to
a TCC phase and the same write also becomes an AT branch, so a rollback tries to undo it twice by two
mechanisms that disagree about what "undo" means.

One header, two protocols, opposite requirements. So each endpoint refuses the other's context:

- `POST /awards` refuses a request **without** an XID (409).
- `POST /reservations` and its settlements refuse a request **with** one (409).

Those guards are not defensive decoration. Measured with the header dropped and the guard removed: the happy
path passes, nothing throws, no log line appears — and a rollback returns the money while keeping the points.
The guard converts that into a 422 the caller cannot miss.

## Two Spring Boot applications, one classpath

The end-to-end module boots both services in one JVM, which found three collisions that separate processes
would have hidden — all three fixed in the services rather than worked around in the tests:

| Collision | Fix |
| --- | --- |
| The account app's base package was `..s10`, swallowing the points service's beans | Root each app at its own context package |
| Both shipped `application.yaml`; one wins, the other boots on its neighbour's datasource | `account-service.yaml` / `points-service.yaml`, named by each app's own `application()` builder |
| Both shipped `db/migration/V1__*.sql`; Flyway: "Found more than one migration with version 1" | `db/migration/account` and `db/migration/points` |

And one more, in the harness itself: `SpringApplicationBuilder.properties(...)` lands in `defaultProperties`,
which sits *below* the application's own yaml — so overrides passed that way are read, ignored, and impossible
to notice. They go in as command-line arguments.

## Code tour

| Concern | Where | Verified by |
| --- | --- | --- |
| The global transaction boundary | `PointsPurchase` | `theedgeDoesNotOwnTheTransactionBoundary` |
| The debit, unaware of any of this | `DebitAccountHandler`, `Account` | `AccountTest` (4, no DB) |
| AT participant + XID propagation | `HttpPointsParticipant` | commit and rollback tests |
| TCC contract and its three phases | `PointsAwardAction`, `HttpPointsAwardAction` | confirm and cancel tests |
| The participant's model, both shapes | `PointsAccount` | `PointsAccountTest` (10, no DB) |
| Protocol refusals at the edge | `PointsController` | two 409 tests |
| Both interceptors' SQL surviving AT | the undo-log assertions | rollback test |
| Lock cost, AT versus TCC | one shared points row | the measurement test |
| The domain not knowing Seata exists | ArchUnit | 5 rules across both services |

## Five negative controls, each run on its own

| Change | Red | What it measured |
| --- | --- | --- |
| AT client drops `TX_XID` | exactly 2, both a loud 422 `banking.points-refused` | the guard works |
| Same, **plus** the participant's guard removed | 1 — and the happy path still passes with no error at all | rollback returns the money, keeps the points, reports nothing. The reason the guard exists |
| `enable-auto-data-source-proxy: false` in points-service | exactly 2 | same corruption from a different cause — and one the XID guard **cannot** catch, since the XID is present and only the DataSource is unproxied. No points undo log is written at all |
| `@BusinessActionContextParameter` on the interface only | exactly 2 | Java does not inherit parameter annotations: the action context loses every business value, Confirm fails on the missing one, and the coordinator retries every second forever while the account row stays locked. A retry storm, not an error |
| `reserve` no longer refuses a cancelled reference | exactly 2 (1 unit, 1 end-to-end) | the suspension hazard, caught first at the cheapest layer |

## Not demonstrated here

| | |
| --- | --- |
| XA mode | Needs two-phase-capable drivers and holds database locks for the whole transaction — strictly worse than AT here, and its trade-off is a database question rather than a modelling one. Argued in the companion. |
| Seata Saga | It is a state-machine engine defined in JSON, which competes with S9's process manager rather than with this. |
| `store.mode=db` or `raft` for the coordinator | The compose file uses file storage and says so: a coordinator restart loses in-flight transactions, leaving them half-applied with locks held. A real deployment concern with no modelling content. |
| Outbox rows inside a global transaction | Deliberately absent, and the reason is a rule rather than an omission: an outbox row written in an AT branch is deleted by the rollback, but a relay that polled in the meantime has already published. Publishing must key on global commit, which neither this library nor Seata does for you. Named in the companion as the one combination to avoid. |
| A process crash mid-rollback | One JVM cannot honestly test it. The coordinator retries; the lock stays; the row is unwritable until it succeeds — which is the AT failure mode to plan for. |
