# Configuration reference

Every `aipersimmon.ddd.*` property, its default, and what changing it does. A property only exists
when its module is on the classpath.

**The rule that explains most of this file:** adding a module does not turn its behaviour on.
Everything that writes tables, polls in the background, or rewrites SQL is off or empty by default.
The exceptions are the ones that cannot hurt you — error mapping, request ids, OpenAPI.

Ordered roughly as you meet them.

---

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
| `idempotency.enabled` | `false` | Replays a stored response for a repeated `Idempotency-Key` instead of re-running the side effect. |
| `idempotency.header` | `Idempotency-Key` | Where the key is read from. |
| `idempotency.ttl` | `24h` | How long a stored response stays replayable. |
| `idempotency.require-key` | `false` | Reject a covered request that carries no key, rather than letting it through unprotected. |
| `idempotency.methods` | `POST, PUT, PATCH, DELETE` | Which methods are covered. |

### Replay protection (off by default; needs a `RequestSignatureVerifier` bean)

| Property | Default | Effect |
| --- | --- | --- |
| `replay.enabled` | `false` | Verifies the signature and rejects a stale or replayed request. |
| `replay.tolerance` | `5m` | How far the timestamp may drift before rejection. |
| `replay.signature-header` / `replay.timestamp-header` | `X-Signature` / `X-Timestamp` | Where they are read from. |
| `replay.nonce.enabled` | `false` | Also require a single-use nonce — signature plus timestamp alone allow replay inside the tolerance window. |
| `replay.nonce.header` | `X-Nonce` | Where the nonce is read from. |

### Rate limiting (off by default)

| Property | Default | Effect |
| --- | --- | --- |
| `rate-limit.enabled` | `false` | Registers the limiter. |
| `rate-limit.limit` / `rate-limit.window` | `100` / `1m` | Requests permitted per window. |
| `rate-limit.key` | `ip` | What identifies a caller: `ip` or `header`. |
| `rate-limit.key-header` | `X-Api-Key` | The header used when `key=header`. |
| `rate-limit.headers` | `ietf` | Which `RateLimit-*` response headers to emit. |

> Enabling any of the three without a `-web-store-jdbc` / `-web-store-redis` module (or your own store
> bean) means an in-memory store: state per JVM, so a second instance stops honouring the protection.
> Startup WARNs, naming what breaks. `allow-in-memory-stores=false` turns that into a failure.

## `aipersimmon.ddd.tenancy` — multi-tenancy

Entirely inert until enabled, so bundling it costs nothing at N=1.

| Property | Default | Effect |
| --- | --- | --- |
| `enabled` | `false` | Resolves the tenant at the edge, binds `TenantContext` for the request, and seeds `CommandContext.tenantId`. |
| `header` | `X-Tenant-Id` | Where the default resolver reads the tenant. Replace `TenantResolver` for JWT claims, subdomains, and so on. |
| `missing-policy` | `REJECT` | What to do when no tenant resolves: `REJECT` (fail the request) or `ROOT` (fall back to the single-tenant sentinel). `REJECT` is the safe default — silently defaulting to a tenant is how data crosses tenants. |
| `exclude-paths` | `/actuator/**` | Paths the resolution filter skips. Probes have no tenant. |
| `mybatis-plus.tenant-column` | `tenant_id` | The discriminator column name. |
| `mybatis-plus.tenant-tables` | (empty) | **Opt-in allow-list** of tables the interceptor rewrites. Empty means no rewriting: naming your tables here is the deliberate act. The framework's own background-polled tables are deliberately absent — a poller runs with no request tenant. |

## `aipersimmon.ddd.flyway` — the framework's own schema

| Property | Default | Effect |
| --- | --- | --- |
| `enabled` | `true` | Whether the runner applies anything at all. It applies only what `components` lists, so `true` is safe. |
| `components` | (empty) | Which component schemas to apply: `outbox`, `inbox`, `process-manager`, `operation-log`, `web-store`. **Empty creates nothing.** |
| `baseline-on-migrate` / `baseline-version` | `true` / `0` | Lets the framework's migrations start cleanly on a database that already has your tables. |
| `history-table-prefix` | `flyway_schema_history_aipersimmon_` | Each component gets its own history table, so framework migrations never interleave with yours. |

Managing schema yourself is fine: copy the migrations from
`aipersimmon/db/migration/<component>/<vendor>/` on the classpath into your own tool and leave
`components` empty.

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
| `relay.enabled` | `true` | Whether the relay is *scheduled*. `false` removes only the schedule, not the relay: nothing polls on its own, and a caller can drive `OutboxRelay.relay()` directly with no lock in the way. Use it when one dedicated instance relays while the rest only write, or in an integration test that asserts on what a single poll did. |
| `poll-delay-ms` | `1000` | How often the relay looks for unsent rows, *after* the first poll. `@Scheduled(fixedDelay)` runs first and waits afterwards, so raising this does not prevent a poll at startup — that is what `relay.enabled=false` is for. Lower means lower latency and more empty queries. |
| `batch-size` | `100` | Rows per poll. See the budget note below. |
| `max-attempts` | `10` | Attempts before a row moves to the dead-letter table. A *permanent* failure (unknown type, malformed payload) skips straight there — retrying cannot fix it. |
| `retry.base-backoff-ms` / `retry.max-backoff-ms` | `1000` / `60000` | Exponential backoff between attempts on a transient failure. |
| `relay.lock-name` | `${spring.application.name}` | The ShedLock name that keeps one instance polling at a time. |
| `relay.lock-at-most-for` | `PT60M` | Lease length. **Keep `batch-size × producer.send-timeout-ms` below this**: a whole poll of stalled sends can otherwise outlive the lease and let a second instance dispatch the same rows. Startup WARNs if the shipped arithmetic is broken. |
| `cleanup.enabled` | `false` | Deletes sent rows past retention. Off by default — an unbounded table is visible, whereas deleting rows someone still wanted is not. |
| `cleanup.retention-seconds` | `604800` (7 days) | How long a sent row is kept. |
| `cleanup.poll-delay-ms` | `3600000` (1 hour) | How often cleanup runs. |
| `cleanup.lock-name` / `cleanup.lock-at-most-for` | `${spring.application.name}` / `PT10M` | ShedLock settings for cleanup. |

## `aipersimmon.ddd.inbox` — idempotent consumer

| Property | Default | Effect |
| --- | --- | --- |
| `consumer` | `${spring.application.name}`, else `aipersimmon` | This application's identity in the dedup key. Several services sharing one inbox table must differ here, or they suppress each other's processing of the same message. |

The dedup key is `(consumer, source, message_key)` — the producer's `ce_source` and the message's
`ce_id`. `ce_id` is unique only *within* its source, which is all CloudEvents requires, so the pair is
what identifies a message globally. Keying on the id alone would drop a message from one producer
because a *different* producer had already used that id — silently, as a phantom duplicate. It costs
nothing while every producer mints UUIDs, and breaks the moment one uses per-source sequence numbers.

| `cleanup.retention-seconds` | `2592000` (30 days) | How long a handled key is remembered. Must exceed the longest possible redelivery delay, or a very late redelivery is processed twice. |
| `cleanup.poll-delay-ms` | `3600000` (1 hour) | How often cleanup runs. |

## `aipersimmon.ddd.messaging.kafka` — broker transport

| Property | Default | Effect |
| --- | --- | --- |
| `topic` | `aipersimmon.integration-events` | Fallback topic. Per-event routing comes from `@Externalized("...")`, which may itself contain a `${property}` placeholder. |
| `producer.send-timeout-ms` | `30000` | How long the relay waits for a broker ack per message. Feeds the lease-budget arithmetic above. |
| `consumer.enabled` | `false` | Registers the consumer bridge. Off by default because publishing and consuming are separate decisions — a service may only produce. |
| `consumer.group-id` | `${spring.application.name}`, else `aipersimmon` | The consumer group. |
| `consumer.skip-locally-unhandled` | `true` | Drop a record whose `(type, version)` no local `@EventListener` handles, before the inbox. Set `false` if you consume through a mechanism the scan cannot see. |
| `consumer.retry.max-retries` | `3` | Retries for an *ambiguous* failure before the record is dead-lettered to `<topic>.DLT`. |
| `consumer.retry.initial-interval-ms` / `multiplier` / `max-interval-ms` | `1000` / `2.0` / `10000` | That retry's exponential backoff. |
| `consumer.systemic-backoff-interval-ms` | `10000` | Retry interval for a *systemic* failure (a `DataAccessException`: database down, pool exhausted). These are retried **indefinitely and never dead-lettered** — the partition waits at the record so healthy messages are not flooded into the DLT. Each retry WARNs with the record and the cause, so a stalled partition is visible. Raise this to make a long outage quieter. |

Three failure tiers, worth knowing before tuning: **poison** (unknown type, malformed payload) is
dead-lettered at once; **systemic** is retried forever and never dead-lettered; **everything else**
gets the bounded backoff and then the DLT.

## `aipersimmon.ddd.process-manager` — durable process manager

| Property | Default | Effect |
| --- | --- | --- |
| `enabled` | `true` | Registers the runtime, relay and deadline worker. |
| `dialect` | `auto` | SQL dialect for the claim query; detected from the `DataSource`. |
| `worker-id` | (generated) | Identity in a lease. Leave it generated unless you need stable ids in logs. |
| `schema-validation` | `validate` | Whether to check the four tables exist at startup. `validate` fails fast instead of at the first transition. |
| `start-duplicate-business-key` | `reject` | What a second start for the same business key does: `reject`, or `ignore` for an idempotent trigger. |
| `concurrency-max-retries` | `3` | In-process retries when two workers race one instance. |
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
| `observability.stuck-threshold` | `15m` | How long before an instance counts as stuck in the metrics. |
| `observability.oldest-pending-warn` | `60s` | Backlog age that WARNs — the signal that the relay is falling behind. |

## `aipersimmon.ddd.operation-log` — business audit log

Records only commands carrying `@OperationLog`, so adding the module logs nothing by itself.

| Property | Default | Effect |
| --- | --- | --- |
| `source` | (empty) | Recorded as the origin system. |
| `tenant.enabled` | `false` | Whether to record the tenant on each row. |
| `limits.summary-max-chars` | `1024` | Truncation cap for the rendered summary. |
| `limits.max-changes` / `max-details` | `20` / `20` | Caps on recorded field changes and detail entries. |
| `limits.max-value-chars` | `512` | Per-value truncation. |

The limits exist so one pathological command cannot write an unbounded audit row. Raise them
deliberately.

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
4. `batch-size × producer.send-timeout-ms` below `relay.lock-at-most-for`.
5. `inbox.cleanup.retention-seconds` longer than your worst redelivery delay.
6. `tenancy.mybatis-plus.tenant-tables` lists every tenant-owned table, if tenancy is on.
7. `outbox.cleanup.enabled` considered — decided either way, not left unread.
