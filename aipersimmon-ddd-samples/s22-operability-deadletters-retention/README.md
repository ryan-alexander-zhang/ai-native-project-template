# S22 — Dead letters, replay, retention, and the guards that speak at startup

Every other sample here shows a flow working. This one is about the four things that decide whether the
flow can be *run*: what happens to a message that cannot be delivered, how an operator gets it back,
which tables grow forever, and what the framework says when it has been asked to do something it cannot.

Companion document: `docs/analysis/analysis-00035-samples-operability-deadletters-retention.md`.

## Run it

```bash
mvn -pl s22-operability-deadletters-retention/ordering-service  -am verify    # 31 tests
mvn -pl s22-operability-deadletters-retention/inventory-service -am verify    # 11 tests
```

Real PostgreSQL and real Kafka via Testcontainers; they **skip** rather than fail without Docker.

**The broker runs with topic auto-creation off**, which is not a detail. It is the single setting that
makes half of this sample observable at all — see below.

## The one setting this sample would be a lie without

`StrictKafka` starts the broker with `KAFKA_AUTO_CREATE_TOPICS_ENABLE=false`. With auto-creation on — the
broker's default, and what every other sample here runs against:

| | with auto-creation on | with it off |
| --- | --- | --- |
| publishing to an unprovisioned topic | silently succeeds, creating a topic no consumer reads | fails, and becomes a dead letter |
| a poison record with no `<topic>.DLT` | the DLT is created by the publish that needs it | the recovery fails and the partition stalls forever |

Both hazards are *invisible* in a permissive environment and both appear in the strict one, which in
most organisations is production. A sample that ran against the default would have demonstrated nothing
and would have taught the wrong lesson twice.

## Publishing: how a message stops being live

Two reasons, and the difference decides what an operator does next.

| Reason | Cause here | Attempts | What replaying it does |
| --- | --- | --- | --- |
| `RETRIES_EXHAUSTED` | the destination topic was never provisioned | the ceiling (3) | worth pressing, **after** creating the topic |
| `PERMANENT` | a row for an event class this deploy retired | 1 — no retries wasted | nothing, until something changes |

Neither is injected. The first is a real Kafka producer failing to find a topic on a real broker; the
second is a row written directly into the outbox, because a permanent failure means a `(type, version)`
no local class answers — which cannot be produced by code that is currently deployed. The real cause is
a deploy that retired an event class while unsent rows for it were still in the table, and a row written
by hand is exactly what that leaves behind.

There is an asymmetry worth carrying away: an **externalized** leftover would still ship, because the
broker does not need your class to accept the bytes. Only the in-process leg has to reconstruct the
event, so the publisher's permanent failures are the ones it must decode on the way out. Poison that
travels over a broker surfaces at the *consumer* — which is the other half of this sample.

## The row moves, and that is a design decision

A spent message is **moved** out of `aipersimmon_outbox` into `aipersimmon_dead_letter`, in one
transaction. A "gave up" flag on the original row would have to be either still selectable (a poll
re-attempting a hopeless row every second) or an unselectable tombstone on the write-hot table that
every command inserts into. Both are paid for by the business transaction. Moving it means the hot table
holds only live work.

The cost is stated by a test rather than hidden: **giving up unblocks the aggregate.** Only the head of a
subject's queue is claimable, so a retired row lets its successor ship — and the downstream then sees
event 2 without event 1. The alternative trades a gap for a stall, and a stall on one aggregate is
invisible while the rest of the traffic flows. Neither is free, which is why one dead letter deserves an
alert: "a message was set aside" and "a consumer's view of that aggregate is now wrong" are the same
event.

## `/ops/dead-letters` — the endpoint whose absence is the bug

Three routes: list (cursor-paged), find by id, replay. The library splits `DeadLetters` (read) from
`DeadLetterStore` (store and replay) precisely because `replay` takes an event id the caller must
already have — and before the read port existed, the only place to get one was a hand-written query
against a table the application does not own. **A service that ships an outbox and no listing has
quarantined its messages into a room with no door.**

No payload is returned. Triage asks "why did this not go out, and is it worth replaying", which the body
answers for neither, and a listing carrying every message body is both expensive and a way to spill
event contents onto an operations screen.

### Replay is a button, not a change-controlled procedure

Nothing in the library or in this service can verify that the underlying cause is gone — no code can. So
the arrangement is to make a wrong replay **cheap** instead of impossible: the message goes back unsent
with its attempts reset, fails the same way, and returns to the same table. `ReplayAfterTheFixTest`
walks the whole runbook in one method — publish into nothing, see the give-up, **create the topic**,
replay, watch it arrive — because the middle step is the one nobody can automate and the two halves
either side of it only mean something with it in between.

What makes replay safe to press twice is not this service: the event **keeps the id it was born with**,
so a consumer that already saw it recognises the duplicate through its inbox — the same `(source, ce_id)`
key that absorbs the relay's own at-least-once redeliveries. A "replay" that minted a fresh event id
would be a second event about the same fact, and no downstream dedup could catch it. Pressing the button
twice is a 404, idempotent by consequence rather than by a guard.

### What `lastError` does not tell you

Measured, and it is a real gap: the relay records only the outermost exception, so the most common
publish failure of all reads as `org.springframework.kafka.KafkaException: Send failed` — the topic name
and the actual cause (`Topic … not present in metadata`, `UnknownTopicOrPartitionException`) are two
levels down the cause chain and discarded. Filed as **issue-00165**. `DeadLetterTest` asserts the
current behaviour, including that the topic name is *absent*, rather than matching a substring of
whatever came out.

## Consuming: what a partition does with a record it cannot handle

Three tiers, and the library refuses to collapse them into one retry policy.

| The failure | Classified by | Retries | Ends up |
| --- | --- | --- | --- |
| poison — unknown type, malformed, unparseable | exception type | none; skips the backoff | `<topic>.DLT`, at once |
| systemic — a `DataAccessException` | exception type | **unbounded**, at a fixed interval | nowhere; the partition waits |
| anything else | — | bounded backoff | `<topic>.DLT`, as a safety net |

A single policy has to choose, and either choice is a disaster somewhere. Bounded-retries-then-DLT is
right for a bad record and catastrophic for a ten-minute database outage: the partition drains itself
into the DLT at retry speed, and a blip becomes a manual replay of everything that arrived during it.
Unbounded retries are right for the outage and catastrophic for a bad record. So the library claims
certainty only for the one signal that carries it and takes the stall deliberately —
`SystemicFailureTest` measures both halves, including the payoff: **nobody replays anything, nobody
restarts anything; recovery is the next retry succeeding.**

## The sharpest pair in the sample

`PoisonWithoutDltTest` and `PoisonWithDltTest` run the **same application, same configuration, same
record**. The only difference is whether `<topic>.DLT` exists.

| | `.DLT` missing | `.DLT` present |
| --- | --- | --- |
| the poison record | redelivered forever | on the DLT, whole, with its `ce_` headers and the exception |
| the healthy record behind it | never consumed | consumed |
| what anyone sees | a service that is up, healthy, and not moving | one record set aside |

A consumer's topic list is **two topics per subscription**, and the second one's name appears in no
configuration file — the error handler derives it. The library deliberately neither auto-creates it nor
probes for it (a probe would false-fail every auto-creating environment and could only warn in the
rest), so knowing this is the deployment's job.

The stall is the worst failure in the sample, and not because of the stall: it is because nothing says
so where anyone is looking. Health probes pass, the group exists, and lag grows on one partition. **Per-
partition consumer lag is the alert that turns this into a page**; there is no substitute for it here.

## Retention: five components, three different defaults

The catalogue asked about four framework tables. There are five migration components — `outbox`,
`inbox`, `process-manager`, `operation-log`, `web-store` — and their purge defaults are not uniform.

| Component | Purge default | Measured how |
| --- | --- | --- |
| `outbox` (sent rows) | **off** | `RetentionTest`: no `OutboxCleanup` bean; `PurgeTest` with it on |
| `aipersimmon_dead_letter` | **never purged at all** | `PurgeTest`: the sweep leaves it alone |
| `inbox` | **off** | `InboxRetentionTest` (the window, not the job) |
| `process-manager` | off | read from the library's source |
| `operation-log` | off | read from the library's source |
| `web-store` | **on**, hourly | `WebStoreCleanupProperties.java:17` |

Off is the right default for four of them: deleting rows is irreversible and the correct window is a
property of the deployment. The web store is on because those rows carry an `expires_at` the store
itself wrote, so sweeping them destroys nothing anyone could want — which is the actual rule underneath
all six answers. **A retention default may be "on" only when the rows already say when they stop
mattering.**

The cost of "off" is that a service which never sets it grows a table forever and the first person to
notice is a DBA. Off-by-default is only a good default when the omission is visible, and for these
tables that means a metric — the one thing this sample cannot supply from inside itself.

### The inbox's window is a correctness setting

The only one of the six that is. An inbox row is not a record of what happened; it is the thing that
makes the next delivery a no-op. Purge it and the same message is no longer a duplicate — it is a new
message with the same content. `InboxRetentionTest` measures the consequence: **six reserved for an
order of three, no error, nothing in the logs.**

So the window is the maximum over every path by which the same message could still arrive: the broker's
retention, **plus** a consumer group reset to the beginning during a recovery, **plus** a dead letter
someone replays a fortnight later, **plus** a publisher whose own outbox was stuck. The middle two are
invisible from the consuming side, which is why picking this number from `retention.ms` is the mistake.

## Startup: refusing to start is the cheap failure

`aipersimmon.ddd.flyway.components` is what applies the framework's migrations — being on the classpath
is not being applied. Forgetting an entry is a normal mistake with an abnormal blast radius, because the
outbox insert runs *inside* the business transaction: a missing table does not break publishing, it
breaks every command that publishes.

`StartupSelfCheckTest` boots three times, and the third is the argument:

| Configuration | Result |
| --- | --- |
| component list empty | refuses to start, naming the property, the manual alternative, and the escape hatch |
| component listed | starts (the control — without it this class measures its own harness) |
| list empty **and** `schema-validation=none` | **starts**, then every command rolls back with a SQL error about a table the application's own developers never wrote |

The third one is up, healthy by every probe it has, and answering 500 to a business endpoint because of
a line missing from a list. A deployment that fails to start is caught by the rollout; that one is
caught by customers.

## Capability degradation: what the framework says when it cannot do what you asked

| Missing capability | What happens | Why that posture |
| --- | --- | --- |
| `@Externalized` events, no transport that reaches outside | **startup fails**; one property accepts it | nothing observable would ever reveal the loss |
| the same, override set | starts, WARN on every boot | the workaround is the supported one, and it still says what is lost |
| an edge guard on the in-memory store | starts, WARN | correct for one instance; the framework cannot see the replica count |
| the same, `allow-in-memory-stores=false` | **startup fails** | the line a production profile carries |
| no `IdGenerator` | startup fails (cited, not tested — the bundle starter makes it unreachable) | `CommandTransactionGuard.java:22` states the posture |

Note the asymmetry: for the transport, strict is the default and the escape hatch is opt-in; for the
in-memory store, permissive is the default and strictness is opt-in. Both are defensible and there is no
single rule — but there is a single **question**, and it is the thing worth taking away:

> **Can the absence be noticed later by anything at all?** If not, it has to be a startup failure. If it
> can — a metric, a duplicate charge, a log line — a WARN plus a strict switch is enough, and refusing to
> start would only teach people to set the switch.

Publishing into a dead end fails that test outright: the relay marks every event sent, so there is no
exception, no dead letter and no consumer lag. From every angle the publisher has a clean bill of health
and the downstream simply never hears from you.

## Multiple instances

The interesting question is not how to stop three relays colliding — it is what happens when one dies
mid-poll, because that is what a rolling deploy does several times a week.

The relay leases **rows**; the purge locks the **schedule**. `MultiInstanceTest` measures both: a row
held by a dead instance is skipped and returns on its own when its lease expires, everything else keeps
flowing, and the relay holds no ShedLock lease at all (`PurgeTest` asserts the row this one asserts the
absence of).

So the configuration is the plain one:

| Knob | Setting | Because |
| --- | --- | --- |
| `outbox.relay.enabled` | `true` **everywhere** | every instance polls; they take disjoint rows |
| `outbox.relay.lease-duration` | how fast a dead instance's rows should come back | a poll bounds itself at half the lease, so this is not sized for slow batches |
| `outbox.poll-delay-ms` | delivery latency you will accept | it is a floor on latency, not a throughput knob |
| `spring.application.name` | distinct per deployment | it *is* the ShedLock lock name; two deployments sharing it take each other's lease |
| `outbox.cleanup.*` | one setting, all instances | ShedLock makes exactly one of them sweep |

Pinning the relay to a single instance buys nothing and costs the property that made delivery survive a
restart.

## Two producer timeouts that are not the same timeout

`aipersimmon.ddd.messaging.kafka.producer.send-timeout-ms` bounds waiting for the broker's
acknowledgement, measured from **after** the record was handed over (`KafkaOutboxDispatcher` computes its
deadline once `send()` has returned). Metadata blocking happens **inside** `send()`, before that deadline
exists, and is bounded only by the producer's own `max.block.ms`.

A topic that does not exist is precisely the case that blocks in metadata rather than in the ack. With
the 60-second default, a relay poll spends a minute per unknown-topic row on the one thread that
delivers everything — and on the consuming side, a missing `.DLT` makes every recovery attempt cost the
same minute while the partition does nothing. Both services set it to five seconds and say why. The
library's stated arithmetic ("keep `send-timeout-ms` under half of `lease-duration`") should include this
value too.

## Five negative controls, each run on its own

| Change | Red | What it measured |
| --- | --- | --- |
| `PoisonWithDltTest` without the `.DLT` topic | exactly 1 | the timeout is on "the record behind the poison is processed" — the DLT is what keeps the partition moving |
| broker with auto-creation **on** (publisher) | exactly 2 | both dead-letter tests time out: with a topic auto-created there is no failure to triage. The permanent-failure tests stay green, confirming the two classes are independent |
| the outage throws a plain `IllegalStateException` | exactly 1 | a healthy `OrderPlaced` lands on the `.DLT`: the classification is by exception type, and getting it wrong flushes good work into quarantine |
| `InboxRetentionTest` without the purge | exactly 1 | the redelivery is deduped and never doubles — the purge is what caused the double effect |
| the ops controller injects `DeadLetterStore` directly | exactly 1 (ArchUnit, 3 violations) | the rule is not vacuous |

## Not demonstrated here

| | |
| --- | --- |
| Metrics and alerting | The gap this sample can name but not fill. The library reports through `OutboxObserver`/Micrometer and S15 covers the exporter; the thresholds (dead letters > 0, per-partition lag, unsent age) are a monitoring exercise. |
| Process-manager and operation-log retention | Their tables are not in this sample; the knobs have the same shape and S9 owns the process tables. |
| The web store's sweep | Its own defaults are cited, not run: S2 uses Redis (TTL, no purge) and S7 the JDBC store. |
| Replaying from the consumer's `.DLT` | The publisher's replay is an endpoint over a table it owns; a DLT replay is a topic-to-topic copy with a different authorisation story. |
| Bulk replay | One id at a time, on purpose. "Replay everything" during an incident is how a second incident starts. |
| Securing `/ops` | A separate controller so the split can be made, and no security module to make it with. |
| Schema migration ordering | Which runner goes first, and migrating a live table — S23. |
| Tenancy on the operations surface | S4/S13's subject. A dead letter carries `tenant_id`; whether an operator sees every tenant's is a policy question this sample does not answer. |
