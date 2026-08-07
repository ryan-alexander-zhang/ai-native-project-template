# Configuration reference

Every `aipersimmon.ddd.*` property, its default, and what changing it does. A property only exists
when its module is on the classpath.

**The rule that explains most of this file:** adding a module does not turn its behaviour on.
Everything that writes tables, polls in the background, or rewrites SQL is off or empty by default.
The exceptions are the ones that cannot hurt you — error mapping, request ids, OpenAPI.

Ordered roughly as you meet them.

---

## `aipersimmon.ddd.cqrs` — the command bus

| Property | Default | Effect |
| --- | --- | --- |
| `transaction.required` | `true` | Whether the application refuses to start without a `PlatformTransactionManager`. |
| `retry-on-conflict.enabled` | `false` | Whether an optimistic-locking conflict is retried inside the bus instead of surfacing to the caller. Off by default because turning it on is an assertion about *your* handlers: a retry reruns the whole handler, so anything it did outside the transaction (a mail, a third-party call) happens again. Leave it off and a conflict reaches the caller, which is the honest outcome when rerunning is not known to be safe. |
| `retry-on-conflict.max-attempts` | `3` | Total attempts, the first included — `3` means "retry twice, then let the conflict stand". |
| `retry-on-conflict.initial-backoff` | `50ms` | Backoff before the first retry; doubles per further retry. |

The starter's guarantee is that one command is one transaction: the aggregate write, the outbox row
and the domain events commit together or not at all. That is implemented by two beans conditional on a
transaction manager, so without one they are simply absent — every command runs untransacted and
nothing says so. Hence the refusal, and a startup report naming both ways out. Set it to `false` for a
service that deliberately has no database; that logs a WARN on every start, so it cannot become the
unnoticed state of a service that later grows one.

Independently of this setting, the aggregate repository bases and the outbox writer each refuse to
write outside a transaction — the guarantee is checked where it is relied on, not only where it is
configured.

## `aipersimmon.ddd.integration` — event identity

| Property | Default | Effect |
| --- | --- | --- |
| `source` | `${spring.application.name}`, else `aipersimmon` | The CloudEvents `ce_source` on every integration event this service publishes: who produced it. Set it explicitly if the application name is not a stable service identity. |
| `scan-packages` | (auto-configuration packages) | Where to scan for `IntegrationEvent` classes when building the type registry and the `@Externalized` routing table. Widen it only if your events live outside the application's own packages. |

## `aipersimmon.ddd.web` — the HTTP edge

Two zero-risk concerns default **on**; three stateful ones default **off**.

| Property | Default | Effect |
| --- | --- | --- |
| `problem-details.enabled` | `true` | Maps `DomainException` / `ApplicationException` / validation failures to RFC 9457 problem documents. Turning it off means writing your own advice. |
| `request-id.enabled` | `true` | Registers the request-id filter. |
| `request-id.header` | `X-Request-Id` | Header read for an inbound request id. |
| `request-id.generate-if-absent` | `true` | Mint one when the header is missing, so every log line can be correlated. |
| `allow-in-memory-stores` | `true` | Whether an **enabled** concern below may fall back to its in-memory store. `true` keeps development frictionless. Set `false` in production: startup then fails instead of substituting a store that does not survive a second instance. |

### Idempotency (off by default)

| Property | Default | Effect |
| --- | --- | --- |
| `idempotency.enabled` | `false` | Claims the key before executing, so one key means one execution; later attempts get the stored outcome. |
| `idempotency.header` | `Idempotency-Key` | Where the key is read from. |
| `idempotency.ttl` | `24h` | How long a stored outcome stays replayable — the retry window offered to clients. |
| `idempotency.claim-lease` | `1m` | How long a claim survives without completing. Must outlast the slowest covered request (see below). |
| `idempotency.require-key` | `false` | Reject a covered request that carries no key, rather than letting it through unprotected. |
| `idempotency.methods` | `POST, PUT, PATCH, DELETE` | Which methods are covered. |

The key is claimed **before** the request runs, which is what makes "executed once" true. Looking the
key up, running, then saving the response cannot: two concurrent first attempts both miss the lookup
and both execute, and the atomic save only picks whose response is kept — after both side effects have
committed. That is the ordinary case, not an exotic one; a client whose first attempt timed out retries
while it is still in flight, which is why it sent a key at all.

So a covered request meets one of four answers:

| Claim | Response |
| --- | --- |
| won | executes; a 2xx/4xx outcome is stored, a 5xx is not (see below) |
| already held, not finished | `409 Conflict` + `Retry-After` — there is no outcome yet and executing would duplicate the side effect |
| completed outcome exists | the stored status, body and meaning-carrying headers, replayed verbatim |
| same key, different request | `422 Unprocessable Content` — neither executing nor replaying is right |

Three consequences worth knowing:

- **`claim-lease` bounds the 409 window.** Set it shorter than your slowest covered request and a
  still-running request can have its key claimed by a retry — the duplicate execution the claim
  exists to prevent. Set it far longer and a caller that dies mid-request leaves its key unusable
  until the lease passes. It is deliberately separate from `ttl`, which is the client-facing retry
  window and is normally hours.
- **A 5xx is not stored.** Freezing a transient failure under the key would answer every later retry
  with that failure, defeating the retry the key was issued for. The claim is released instead. A 4xx
  is a decided outcome and is stored like any other.
- **The filter runs after authentication**, unlike the framework's other filters. A key belongs to a
  caller, so the identity is `(tenant, principal, key)`; the principal comes from an
  `IdempotencyPrincipalResolver` bean, which by default reads the Spring Security context when it is
  on the classpath and otherwise resolves to none — correct for an endpoint with no authentication,
  where keys are scoped by tenant alone. Supply your own bean to key on something else, such as the
  client a token was issued to rather than the end user acting through it.

A `fingerprint` of the request (method, path, query, content type and length) is compared but is not
part of the identity — that is what produces the `422`. It deliberately excludes the body: buffering
every request body to hash it would hand an unauthenticated caller a memory cost, and the leak this
guards alongside — one caller reading another's response — is closed by the principal, not the digest.
A key reused against a different endpoint or a differently shaped payload is caught; two distinct
bodies of identical length and type against the same endpoint are not.

### Replay protection (off by default; needs a `RequestSignatureVerifier` bean)

| Property | Default | Effect |
| --- | --- | --- |
| `replay.enabled` | `false` | Verifies the signature and rejects a stale or replayed request. |
| `replay.tolerance` | `5m` | How far the timestamp may drift before rejection. |
| `replay.signature-header` / `replay.timestamp-header` | `X-Signature` / `X-Timestamp` | Where they are read from. |
| `replay.nonce.enabled` | `false` | Also require a single-use nonce, and **remember having seen it** — signature plus timestamp alone allow replay inside the tolerance window. It governs the requirement and the dedup, *not* whether the nonce reaches your verifier: the header is read either way, so a scheme that signs the nonce (which this library recommends) verifies with dedup off. Turning it off means the nonce table goes away and a captured request can be replayed inside the tolerance window; it does not mean signatures stop working. |
| `replay.nonce.header` | `X-Nonce` | Where the nonce is read from — always, so a `SignedRequest` carries whatever the caller sent. |
| `replay.max-body-size` | `1MB` | Largest body buffered before answering `413`. A signature covers the body, so the body must be held in memory *before* the request is known to be authentic — raise this only to the largest signed request you actually accept. |
| `replay.url-patterns` | (empty) | Servlet URL patterns the filter applies to; empty means every request. Set it when unsigned traffic still has to be served — a liveness probe, say. Servlet patterns (`/api/*`, `*.json`, an exact path), not Ant patterns: the container matches, on the path it will really dispatch on. |

### Rate limiting (off by default)

| Property | Default | Effect |
| --- | --- | --- |
| `rate-limit.enabled` | `false` | Registers the limiter. |
| `rate-limit.limit` / `rate-limit.window` | `100` / `1m` | Requests permitted per window. |
| `rate-limit.key` | `ip` | What identifies a caller: `ip` or `header`. |
| `rate-limit.key-header` | `X-Api-Key` | The header used when `key=header`. |
| `rate-limit.headers` | `ietf` | Which `RateLimit-*` response headers to emit. |
| `rate-limit.policy-name` | `default` | Policy name echoed in those headers. Cosmetic to the limiter; it exists so a caller reading `RateLimit-Policy` can tell which of your policies it hit. |

> Enabling any of the three without a `-web-store-mybatis-plus` / `-web-store-redis` module (or your own store
> bean) means an in-memory store: state per JVM, so a second instance stops honouring the protection.
> Startup WARNs, naming what breaks. `allow-in-memory-stores=false` turns that into a failure.

### Web-store cleanup (`-web-store-mybatis-plus` only; on by default)

The three tables each delete expired rows only for the key in front of them, and only when that key
is presented again — which for an idempotency key or a nonce is nearly never. This sweep is what
removes the rest. The Redis store expires keys itself and has no equivalent.

| Property | Default | Effect |
| --- | --- | --- |
| `store.cleanup.enabled` | `true` | Sweeps expired rows periodically. Off means the three tables grow without bound. |
| `store.cleanup.poll-delay` | `1h` | Time between sweeps. |
| `store.cleanup.rate-limit-retention` | `24h` | How long a rate-limit counter is kept after its window began. **Must exceed the longest `rate-limit.window` in use** — the row does not record which policy it counted for. Setting it too short resets a bucket's quota rather than failing a request. |

> On by default, unlike the process manager's retention, and the difference is deliberate: there the
> rows are business records and how long to keep them is your decision, whereas `expires_at` here is
> the store's own statement that the row is dead.

### Web-store schema validation (`-web-store-mybatis-plus` only; on by default)

| Property | Default | Effect |
| --- | --- | --- |
| `store.schema-validation` | `validate` | Whether to check at startup that the three web-store tables exist **and carry the columns of the latest migration**. `validate` fails fast instead of failing every covered request at the edge. `none` disables. |

## `aipersimmon.ddd.tenancy` — multi-tenancy

Entirely inert until enabled, so bundling it costs nothing at N=1.

| Property | Default | Effect |
| --- | --- | --- |
| `enabled` | `false` | Resolves the tenant at the edge, binds `TenantContext` for the request, seeds `CommandContext.tenantId`, and makes a missing binding an error rather than a fall back to the sentinel (see below). |
| `header` | `X-Tenant-Id` | Where the default resolver reads the tenant. Replace `TenantResolver` for JWT claims, subdomains, and so on. |
| `trust-header` | `false` | Whether that header may be believed. With tenancy on and no `TenantResolver` bean of your own, **startup fails** until you either define one or set this to `true`. |
| `missing-policy` | `REJECT` | What to do when a request resolves no tenant: `REJECT` (fail the request with `400`) or `SYSTEM` (bind the `__root__` sentinel; controlled internal/migration use only). |
| `exclude-paths` | `/actuator/**` | Paths the resolution filter skips. Probes have no tenant. Matched against the path the container dispatches on, so a traversal like `/actuator/../orders` cannot borrow an excluded prefix. |
| `mybatis-plus.tenant-column` | `tenant_id` | The discriminator column name. |
| `mybatis-plus.tenant-tables` | (empty) | **Opt-in allow-list** of tables the interceptor rewrites. Empty means no rewriting: naming your tables here is the deliberate act. The framework's own background-polled tables are deliberately absent — a poller runs with no request tenant. |
| `mybatis-plus.exempt-tables` | (empty) | Tables that carry the discriminator column but are deliberately **not** interceptor-scoped, because their repository stamps and filters it itself (a dedup log written from paths with and without a bound tenant, say). Listing a table here is a statement of intent; it is not a way to silence the guard below for a table you have not thought about. |
| `mybatis-plus.guard-tables` | `true` | Whether startup verifies that every base table carrying the discriminator column appears in `tenant-tables` or `exempt-tables`. On by default, and worth leaving on: the allow-list **fails open** — a tenant-carrying table in neither list gets no tenant predicate at all, so it is read across tenants silently. Completeness of an allow-list is exactly the kind of property a machine should check rather than a reviewer. |

### Isolation fails closed

With tenancy enabled, anything that stamps or filters a `tenant_id` resolves the tenant through one
decision point (`TenantContext.effective()`), and that point **throws `MissingTenantException` when
no tenant is bound** instead of quietly using `__root__`. The sentinel fallback survives only while
tenancy is off, where single-tenant is N=1 and every row legitimately carries `__root__`.

This matters because the binding does not follow thread hops. It is established at a trusted
boundary — the edge filter, or a message consumer — and a plain `ThreadLocal` does not survive
`@Async`, a `CompletableFuture` callback, a scheduler thread, or a hand-rolled executor. Under the
old fallback those paths read and wrote the shared sentinel bucket silently: a `SELECT` came back
empty (indistinguishable from "this tenant has no rows") and an `INSERT` landed in a bucket that, in
a migrated deployment, holds pre-migration production data.

Three things follow:

- **`@Async` and injected `TaskExecutor`s keep the tenant.** The starter registers a `TaskDecorator`
  that captures the submitting request's tenant and re-binds it on the worker thread. Spring Boot
  applies it to the executor it auto-configures. It cannot reach executors you construct yourself,
  and it backs off entirely if you define your own `TaskDecorator` bean — Boot honours a decorator
  only when exactly one exists, so contributing a second would silently disable yours. In both cases
  compose tenant propagation into your own decorator, or wrap the work in `TenantContext.runAs(...)`.
- **Background pollers are not tenant-scoped and must not be.** The relay, deadline worker, and
  cleanup jobs run with no request tenant, which is why the framework's own tables are absent from
  `mybatis-plus.tenant-tables`. Adding them there would make every poll fail.
- **A consumed integration event must carry `ce_tenantid`.** With tenancy on, a record missing that
  attribute is rejected as a malformed CloudEvent (permanent failure → dead-letter) rather than
  attributed to the sentinel. Producers that predate tenancy are only accepted while tenancy is off.

### Why the tenant header is not trusted by default

`X-Tenant-Id` is supplied by the caller and nothing in the framework ties it to an authenticated
principal, so believing it means anyone who can reach the service reads and writes any tenant's data
by changing one header. There is no safe default the framework can pick — it cannot know the shape of
your principal — so it refuses to start and asks for the decision:

```yaml
# Option 1 — resolve from the authenticated principal. Define a TenantResolver bean; no opt-in needed.
# Option 2 — keep the header, because a gateway/mesh/BFF authenticates the caller and rewrites it,
#            discarding whatever the client sent:
aipersimmon:
  ddd:
    tenancy:
      enabled: true
      trust-header: true
```

Option 2 holds only while that component cannot be bypassed. If the service is reachable directly —
in-cluster traffic, a port-forward, a misrouted ingress — the header is spoofable again and option 1
is the correct choice.

## `aipersimmon.ddd.flyway` — the framework's own schema

| Property | Default | Effect |
| --- | --- | --- |
| `enabled` | `true` | Whether the runner applies anything at all. It applies only what `components` lists, so `true` is safe. |
| `components` | (empty) | Which component schemas to apply: `outbox`, `inbox`, `process-manager`, `operation-log`, `web-store`. **Empty creates nothing.** |
| `baseline-on-migrate` / `baseline-version` | `true` / `0` | Lets the framework's migrations start cleanly on a database that already has your tables. |
| `history-table-prefix` | `flyway_schema_history_aipersimmon_` | Each component gets its own history table, so framework migrations never interleave with yours. |

**Bundling is not enabling.** The `aipersimmon-ddd-starter-mybatis-plus` bundle puts five
components' migrations on the classpath at once, and being on the classpath does not make the
framework write DDL into your database — you name what you want:

```yaml
aipersimmon:
  ddd:
    flyway:
      components: [outbox, inbox]   # nothing else is created
```

Forgetting the line is not silent: each component validates its own tables at startup and refuses to
start, naming the migration path and the property. A name that matches no migration on the classpath
(a typo, or a module not added yet) is a WARN and is skipped, so a spelling mistake does not become a
failed deployment — the component's own validator is what stops it.

Managing schema yourself is equally fine, and is what an empty list means: copy the migrations from
`aipersimmon/db/migration/<component>/<vendor>/` on the classpath into your own tool.

Two things worth knowing about how this runs. **Each listed component gets its own Flyway instance
and its own history table, all against your schema** — several migration managers over one schema, so
that adding or dropping a component never renumbers anything. **`baseline-on-migrate` is on**, which
is what lets the framework be adopted onto a database that already has your tables; it does not skip
anything, because `baseline-version` is `0` and every component migration is `V1` or later, and every
statement is `IF NOT EXISTS`, so re-running over existing objects is a no-op rather than a failure.
Set `baseline-on-migrate=false` if you would rather Flyway refuse a non-empty schema outright.

The outbox migration also provisions `shedlock`, ShedLock's own standard table (not prefixed
`aipersimmon_`, since that default is what a `LockProvider` expects), with `IF NOT EXISTS` so an
application already managing it is unaffected. The outbox is currently the only component that takes
a ShedLock lease.

## `aipersimmon.ddd.outbox` — transactional outbox

Present with a storage module; the relay polls as soon as it is.

| Property | Default | Effect |
| --- | --- | --- |
| `dispatch` | `in-process` | Which built-in dispatcher delivers a relayed event when no messaging starter and no custom `OutboxDispatcher` bean supplies one. `in-process` republishes it through Spring's event publisher — the correct delivery for a LOCAL event. `logging` only logs it, so it delivers *nothing*; it is for watching the relay work, never for a deployment. An unrecognised value fails startup: a broker is chosen by adding its starter, not here. |
| `allow-unreachable-external-events` | `false` | Lets the application start when it declares `@Externalized` events but the active dispatcher cannot reach an external target — accepting that those events get marked sent without leaving the process. Off, because that loss is invisible: the relay treats a dispatch that returns as delivered, so there is no exception, no dead letter and no consumer lag to alert on. Switch it on for a deliberately broker-less local run. |
| `schema-validation` | `validate` | Whether to check at startup that the outbox tables (and `shedlock`) exist **and carry the columns of the latest migration**. `validate` fails fast instead of rolling back the first command that publishes an `@Externalized` event — the outbox insert runs inside the business transaction. `none` disables. |
| `relay.enabled` | `true` | Whether the relay is *scheduled*. `false` removes only the schedule, not the relay: nothing polls on its own, and a caller can drive `OutboxRelay.relay()` directly. Use it when one dedicated instance relays while the rest only write, or in an integration test that asserts on what a single poll did. |
| `poll-delay-ms` | `1000` | How often the relay looks for unsent rows, *after* the first poll. `@Scheduled(fixedDelay)` runs first and waits afterwards, so raising this does not prevent a poll at startup — that is what `relay.enabled=false` is for. Lower means lower latency and more empty queries. |
| `batch-size` | `100` | Rows one poll may dispatch. The batch is handed to the transport before any of it is waited on, so a larger batch costs a poll roughly the same wall-clock time — it is one round trip either way. Every instance polls, so this bounds one poll's work, not the deployment's throughput. |
| `max-attempts` | `10` | Attempts before a row moves to the dead-letter table. A *permanent* failure (unknown type, malformed payload) skips straight there — retrying cannot fix it. |
| `retry.base-backoff-ms` / `retry.max-backoff-ms` | `1000` / `60000` | Exponential backoff between attempts on a transient failure. |
| `relay.lease-duration` | `PT5M` | How long a poll's claim on a row lasts, and therefore **how long a killed instance's rows stay stuck** — nothing else. The other instances keep delivering throughout; only the rows that instance was holding wait this out. Shorten it for faster recovery. A poll stops after half of it and hands back what it did not reach, so a slow batch cannot overrun it; the one thing to keep true is that a *single* dispatch fits in that half — with Kafka, that `producer.send-timeout-ms` stays below `PT2M30S`. Startup WARNs if a custom configuration breaks it; the cost of crossing the line is duplicate deliveries, which a consumer's inbox dedups. |
| `relay.worker-id` | (generated) | Written into the lease of every row this instance claims, so a stuck relay can be traced to a node. Diagnostics only — it decides nothing. Set it to the pod name to make that answerable from the table alone. |
| `cleanup.enabled` | `false` | Deletes sent rows past retention. Off by default — an unbounded table is visible, whereas deleting rows someone still wanted is not. |
| `cleanup.retention-seconds` | `604800` (7 days) | How long a sent row is kept. |
| `cleanup.poll-delay-ms` | `3600000` (1 hour) | How often cleanup runs. |
| `cleanup.batch-size` | `500` | Rows deleted per page; the purge loops pages until one comes back short. Bounds how long any one delete transaction holds locks on a table the relay is reading — the first purge of a long-lived table is many small transactions, not one giant one. |
| `cleanup.lock-name` / `cleanup.lock-at-most-for` | `${spring.application.name}` / `PT10M` | ShedLock settings for cleanup. |

## `aipersimmon.ddd.inbox` — idempotent consumer

| Property | Default | Effect |
| --- | --- | --- |
| `consumer` | `${spring.application.name}`, else `aipersimmon` | This application's identity in the dedup key. Several services sharing one inbox table must differ here, or they suppress each other's processing of the same message. |
| `schema-validation` | `validate` | Whether to check at startup that `aipersimmon_inbox` exists **and carries the columns of the latest migration**. `validate` fails fast instead of at the first consumed message, on a listener thread. `none` disables. |

The dedup key is `(consumer, source, message_key)` — the producer's `ce_source` and the message's
`ce_id`. `ce_id` is unique only *within* its source, which is all CloudEvents requires, so the pair is
what identifies a message globally. Keying on the id alone would drop a message from one producer
because a *different* producer had already used that id — silently, as a phantom duplicate. It costs
nothing while every producer mints UUIDs, and breaks the moment one uses per-source sequence numbers.

| Property | Default | Effect |
| --- | --- | --- |
| `cleanup.enabled` | `false` | Deletes handled keys past retention. Off by default, and **nothing else under `cleanup` does anything until it is on** — setting a retention alone is silently inert. Off is the safe side: a key dropped too early stops guarding its duplicate, and the reprocessing that follows is silent, whereas a growing table is visible. |
| `cleanup.retention-seconds` | `2592000` (30 days) | How long a handled key is remembered. Must exceed the longest possible redelivery delay, or a very late redelivery is processed twice. |
| `cleanup.poll-delay-ms` | `3600000` (1 hour) | How often cleanup runs. |
| `cleanup.batch-size` | `500` | Rows per time-sliced delete page (the key is composite, so pages advance by timestamp; ties can make a page slightly larger). Same purpose as the outbox's: many small transactions instead of one giant first purge. |

## `aipersimmon.ddd.messaging.kafka` — broker transport

| Property | Default | Effect |
| --- | --- | --- |
| `topic` | `aipersimmon.integration-events` | Fallback topic. Per-event routing comes from `@Externalized("...")`, which may itself contain a `${property}` placeholder. |
| `producer.send-timeout-ms` | `30000` | How long the relay waits for a broker ack, measured from when the record was handed to the producer rather than from when the wait starts. Keep it below half of `outbox.relay.lease-duration`, so one send cannot outlive the claim on the row it is sending; `batch-size` does not enter into it, because the sends overlap — a whole batch going quiet costs one timeout, not one per row. |
| `consumer.enabled` | `false` | Registers the consumer bridge. Off by default because publishing and consuming are separate decisions — a service may only produce. |
| `publishes-externalized-events` | `true` | Whether this service **publishes** the `@Externalized` events it declares. On by default, which turns on the durable-transport guard: `@Externalized` events with a non-durable publisher never leave the JVM, and nothing observable would reveal it, so startup fails. **A service that only consumes must set this to `false`** — see below. Set it wrongly on a real publisher and you publish into a dead end with no exception, no dead letter and no consumer lag. |
| `consumer.group-id` | `${spring.application.name}`, else `aipersimmon` | The consumer group. |
| `consumer.skip-locally-unhandled` | `true` | Drop a record whose `(type, version)` no local `@EventListener` handles, before the inbox. Set `false` if you consume through a mechanism the scan cannot see. |
| `consumer.retry.max-retries` | `3` | Retries for an *ambiguous* failure before the record is dead-lettered to `<topic>.DLT`. |
| `consumer.retry.initial-interval-ms` / `multiplier` / `max-interval-ms` | `1000` / `2.0` / `10000` | That retry's exponential backoff. |
| `consumer.systemic-backoff-interval-ms` | `10000` | Retry interval for a *systemic* failure (a `DataAccessException`: database down, pool exhausted). These are retried **indefinitely and never dead-lettered** — the partition waits at the record so healthy messages are not flooded into the DLT. Each retry WARNs with the record and the cause, so a stalled partition is visible. Raise this to make a long outage quieter. |

Three failure tiers, worth knowing before tuning: **poison** (unknown type, malformed payload) is
dead-lettered at once; **systemic** is retried forever and never dead-lettered; **everything else**
gets the bounded backoff and then the DLT.

**Configuring a service that only consumes.** Two lines, and the second one is not obvious:

```yaml
aipersimmon.ddd.messaging.kafka:
  consumer.enabled: true
  publishes-externalized-events: false   # its @Externalized declarations are SUBSCRIPTIONS
```

A consumer has to declare the contracts it handles `@Externalized`, because that annotation is how
the bridge derives its topic set. So the annotation means one thing on a publisher (where an event
goes) and another on a consumer (which topics to subscribe to), and its presence alone cannot tell
them apart — nor can anything else, since there is no static evidence of a call to
`IntegrationEvents.publish`. Without the second line the durable-transport guard reads the
subscription as a publication and refuses to start, and the remedy it suggests (add a durable outbox
module) makes a consume-only service provision three tables it will never write a row into.

**Provision `<topic>.DLT` for every topic you consume** (or let the broker auto-create topics in
environments where that is acceptable). The framework does not create it and deliberately does not
probe for it at startup — a probe would false-fail every auto-create environment, and could only
warn in the rest. What a missing DLT does is the thing to know: dead-lettering a poison record
fails, the error handler seeks back, and the partition retries that record forever. The failure is
a producer error, not a `DataAccessException`, so the systemic-stall WARN does not fire — watch
consumer lag on the partition, which is the one signal that always shows it.

## `aipersimmon.ddd.process-manager` — durable process manager

| Property | Default | Effect |
| --- | --- | --- |
| `enabled` | `true` | Registers the runtime, the relay, the deadline worker and the parked-input worker. |
| `dialect` | `auto` | SQL dialect for the claim query; detected from the `DataSource`. |
| `worker-id` | (generated) | Identity in a lease. Leave it generated unless you need stable ids in logs. |
| `schema-validation` | `validate` | Whether to check at startup that the four tables exist **and carry the columns of the latest migration**. `validate` fails fast instead of at the first transition — or, worse, on a background worker's poll. |
| `start-duplicate-business-key` | `reject` | What a second start for the same business key does: `reject`, or `fold` to return the existing instance for an idempotent trigger. |
| `concurrency-max-retries` | `3` | In-process retries when two workers race one instance. Applies only when the advance owns its transaction; joined to a caller's transaction, a conflict propagates instead (retrying a doomed transaction cannot succeed). |
| `shutdown-timeout` | `30s` | How long to let in-flight transitions finish. |
| `instance.max-lifetime` | `none` | Optional cap after which an instance is force-terminated — a guard against a flow waiting forever on a fact that will never arrive. |
| `payload.max-bytes` | `1048576` (1 MiB) | Refuses an oversized encoded payload rather than letting the row grow unbounded. |
| `effect-relay.enabled` | `true` | Dispatches decided effects (commands, integration events). |
| `effect-relay.poll-delay` | `500ms` | How often. |
| `effect-relay.batch-size` | `100` | Effects per poll. |
| `effect-relay.lease-duration` | `30s` | Claim lease per worker. |
| `effect-relay.max-attempts` | `12` | Attempts before an effect is parked. |
| `effect-relay.backoff.initial` / `.max` / `.multiplier` / `.jitter` | `1s` / `5m` / `2.0` / `0.2` | Retry schedule. Jitter stops many workers retrying in lockstep. |
| `deadline-worker.*` | same shape and defaults | Fires due deadlines (timeouts, escalations). |
| `parked-input-worker.enabled` | `true` | Replays inputs that arrived while an instance was suspended, once it is active again. Turning it off leaves those inputs owed indefinitely — only appropriate while draining a node. |
| `parked-input-worker.poll-delay` | `1s` | How often to look for instances that still owe a replay. |
| `parked-input-worker.batch-size` | `50` | Instances per poll; every owed input of a picked instance is replayed. |
| `cleanup.enabled` | `false` | Deletes finished instances whose retention has elapsed. Off by default: removing a business record, and choosing how long to keep it, are deployment decisions. Left off, the four tables grow for the lifetime of the deployment. |
| `cleanup.retention-seconds` | `2592000` (30d) | How long a finished instance is kept after its last transition. |
| `cleanup.poll-delay` | `1h` | How often to look for instances to remove. |
| `cleanup.batch-size` | `200` | Instances per run. It bounds how long the delete holds locks on tables the relay and deadline worker are also reading; a run that fills its batch logs that more remain. |
| `observability.stuck-threshold` | `15m` | How long before an instance counts as stuck in the metrics. |
| `observability.oldest-pending-warn` | `60s` | Backlog age that WARNs — the signal that the relay is falling behind. |

Two operational characteristics worth knowing before tuning the relay:

- **Run every node in the same timezone (UTC), and say so in your runbook.** Lease expiries and
  deadline due-times are written from the application clock via `Timestamp.from(Instant)`; the
  instants are correct, but a fleet whose nodes disagree on the JVM default timezone can shift how
  a database column reads them by hours. The design is lease-fenced, so skew manufactures only
  duplicates the inbox absorbs and early/late timer fires — degraded, not corrupt. UTC everywhere
  removes the class of surprise.
- **A single instance drains roughly one effect per poll.** The claim takes only the head of each
  instance's queue (that is what preserves per-instance order), so a transition that stages N
  effects on one instance delivers them in about N × `effect-relay.poll-delay`, not in one batch.
  Throughput across *many* instances scales with `batch-size`; depth within *one* instance scales
  with `poll-delay`. A flow that stages long effect chains on a single instance and cares about
  latency wants a shorter poll delay, not a bigger batch.

Cleanup removes an instance **whole** — snapshot, transitions, effects and deadlines together —
and only once every effect and deadline it holds has settled. Two states look finished and are not:
a terminal decision's staged effects still deliver after it ends, and a DEAD effect or deadline is
the record of a side effect that never landed which an operator can still redrive. Either keeps the
instance, so an application that never redrives its dead work will accumulate exactly those
instances; the dead-work gauges are what surfaces them. It takes no cross-instance lock — two
purges select overlapping ids and the second deletes nothing.

The parked-input worker deliberately has no lease, attempt or backoff knobs: a replay is idempotent
(deduped by the replay transition's unique input id), and an input the definition cannot digest
suspends its instance for operator recovery instead of being retried on a schedule. Exposing those
settings would describe a mechanism that is not there.

## `aipersimmon.ddd.operation-log` — business audit log

Records only commands carrying `@OperationLog`, so adding the module logs nothing by itself.

| Property | Default | Effect |
| --- | --- | --- |
| `source` | (empty) | Recorded as the origin system. |
| `schema-validation` | `validate` | Whether to check at startup that `aipersimmon_operation_log` exists **and carries the columns of the latest migration**. `validate` fails fast instead of rolling back the first `@OperationLog` command at the audit append. `none` disables. |
| `limits.summary-max-chars` | `1024` | Truncation cap for the rendered summary. |
| `limits.max-changes` / `max-details` | `20` / `20` | Caps on recorded field changes and detail entries. |
| `limits.max-value-chars` | `512` | Per-value truncation. |

| Property | Default | Effect |
| --- | --- | --- |
| `cleanup.enabled` | `false` | Deletes audit records past retention. Off by default twice over: deleting data is a deployment decision everywhere, and removing *audit* rows should be a statement someone can be asked about — retention obligations are often regulatory. Nothing else under `cleanup` takes effect until it is on. |
| `cleanup.retention-seconds` | `31536000` (365 days) | How long an audit record is kept. Enabling cleanup asserts this window satisfies your obligations. |
| `cleanup.poll-delay-ms` | `3600000` (1 hour) | How often cleanup runs. |
| `cleanup.batch-size` | `500` | Records deleted per id page; the purge loops pages until one comes back short, so the first purge of a long-lived table is many small transactions. |

The limits exist so one pathological command cannot write an unbounded audit row. Raise them
deliberately.

With a `MeterRegistry` present, the audit metrics bind automatically (`aipersimmon.operation.log.*`
counters and latencies). The one to alert on is `failure.record.lost`: a failure-path audit record
could not be written and was swallowed so the original business exception could propagate — an
audit gap.

There is deliberately no per-component tenant switch. The tenant column is always stamped from the
same trusted scope the command runs under, and enforcement follows the deployment-wide
`aipersimmon.ddd.tenancy.enabled`: with tenancy on, an unbound tenant fails the command instead of
stamping the sentinel; with tenancy off, audit rows carry `__root__`. Reads are tenant-scoped
unconditionally — the read criteria require a tenant id.

## `aipersimmon.ddd.openapi`

| Property | Default | Effect |
| --- | --- | --- |
| `enabled` | `true` | Registers the customizer. |
| `default-problem-responses` | `true` | Documents the RFC 9457 responses the web layer actually emits, so the spec matches behaviour. |

---

## Properties owned by other projects

The framework reads these but does not define them; they belong to Spring Boot, MyBatis-Plus,
springdoc and OpenTelemetry:

| Property | Why it matters here |
| --- | --- |
| `spring.application.name` | Default for the outbox lock name, the inbox consumer identity, the Kafka group id and `ce_source`. Set it. |
| `spring.datasource.*` | The single `DataSource` the framework's tables share with your aggregates. |
| `spring.kafka.*` | Bootstrap servers, serializers, `auto-offset-reset`. |
| `springdoc.swagger-ui.enabled` | Whether Swagger UI is served. |
| `otel.exporter.otlp.endpoint` | Where traces go. |

## A production checklist

1. `spring.application.name` set — several defaults derive from it.
2. `aipersimmon.ddd.web.allow-in-memory-stores=false`, so an unshared store fails startup instead of
   silently weakening an enabled protection.
3. `aipersimmon.ddd.flyway.components` lists exactly the components you use.
4. `producer.send-timeout-ms` below half of `outbox.relay.lease-duration`, and that lease short enough
   that a killed instance's rows coming back that late is acceptable.
5. `inbox.cleanup.retention-seconds` longer than your worst redelivery delay — and note it only
   applies once `inbox.cleanup.enabled=true`. Left off (the default), the retention is inert and the
   inbox table grows for the lifetime of the deployment; that is a valid choice, but make it one.
6. `tenancy.mybatis-plus.tenant-tables` lists every tenant-owned table, if tenancy is on.
7. `outbox.cleanup.enabled`, `inbox.cleanup.enabled` and `operation-log.cleanup.enabled` considered —
   decided either way, not left unread. All three default to off.
