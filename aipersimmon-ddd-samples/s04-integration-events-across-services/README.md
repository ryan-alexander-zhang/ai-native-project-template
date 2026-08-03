# S4 — One fact, two deployables

An order is placed in one service; stock is reserved in another. Between them: a transactional
outbox, a real Kafka broker, and an inbox that turns at-least-once delivery into at-most-once effect.

Companion document: `docs/analysis/analysis-00025-samples-integration-events-across-services.md`.

## Run it

```bash
mvn -pl s04-integration-events-across-services/ordering-service   -am verify   # 9 tests
mvn -pl s04-integration-events-across-services/inventory-service  -am verify   # 9 tests
```

Real PostgreSQL and real Kafka via Testcontainers; they **skip** rather than fail without Docker.

## Two services, no shared jar

`ordering-service` publishes; `inventory-service` consumes. There is deliberately **no third module
holding the event class**. Each side declares its own:

| | publisher's class | consumer's class |
| --- | --- | --- |
| type | `com.example.samples.ordering.OrderPlaced` v1 | the same |
| fields | `orderId`, `customerId`, `lines` | `orderId`, `lines` |

They agree because the wire identity is the logical `(name, version)` pair from `@EventType`, never a
Java class name. A shared contract jar would reintroduce exactly the compile-time coupling the broker
was introduced to remove — bump it and both services must ship together. The consumer's copy carries
only what it reads, which is also why *adding* a field is backward compatible and removing one is not
(evolution proper is S21).

## What each side's tests pin down

**Publishing (`OutboxPublicationTest`)**

| Claim | Test |
| --- | --- |
| The order row and the outbox row commit together | `theOrderRowAndTheOutboxRowCommitTogether` |
| A failure *after* the handler leaves neither | `afailureAfterTheHandlerLeavesNeitherRow` |
| Nothing leaves the service until the relay runs | `nothingLeavesTheServiceUntilTheRelayRuns` |
| The wire carries the contract in its headers, keyed by aggregate | `therelayShipsOneRecordCarryingTheContractOnItsHeaders` |
| A second poll ships nothing | `asecondPollShipsNothingAndTheRowIsMarkedSent` |
| An event without `@Externalized` never reaches the broker | `aneventWithoutExternalizedNeverReachesTheBroker` |

The records are read with a plain `KafkaConsumer`, not the framework's bridge: the claim is about the
wire contract a *foreign* consumer would see — `ce_type`, `ce_id`, `ce_source`, `ce_subject`,
`ce_tenantid`, `ce_correlationid`, and `key == orderId` so one order's events stay in one partition.

**Consuming (`InboxConsumptionTest`, `InboxSemanticsTest`)**

| Claim | Test |
| --- | --- |
| A consumed event reserves stock | `aconsumedEventReservesStock` |
| A redelivery changes nothing | `aredeliveryOfTheSameMessageChangesNothing` |
| Two producers that minted the same id are not confused | `twoProducersThatMintedTheSameIdAreNotMistakenForEachOther` |
| An unknown type is dead-lettered, not retried forever | `anunknownEventTypeIsDeadLetteredRatherThanRetriedForever` |
| An unknown *version* is poison too | `aversionThisConsumerDoesNotKnowIsAlsoPoison` |
| The inbox's own contract | `InboxSemanticsTest` |

The records are *produced* by the test rather than by booting the publisher, because the contract
between the services is the wire format — and because a test can then produce what a well-behaved
publisher never would, which is where the interesting behaviour lives.

## The bug this sample had, and what it teaches

`ReserveStockHandler` used to consult the `Inbox` itself. Every message was then silently skipped:

```
consumed ✓   inbox row written ✓   stock untouched ✗   no exception, no dead letter
```

**The consumer bridge already deduplicates.** `KafkaIntegrationEventListener:152` calls
`inbox.alreadyProcessed(ce_source, ce_id)` and drops a redelivery *before* publishing the event
locally. A handler that checks again always finds the bridge's own record and skips the work.

So, with the framework's transport, deduplication is not the handler's job. Where it *is* the
handler's job is a transport the bridge does not drive — a foreign system's messages, translated by
an adapter of your own (S5). There the `Inbox` port is called inside the command's transaction, so the
record and the effect commit or roll back together.

Getting from the symptom to the cause took four eliminations, all recorded in the companion document.
Worth noting which one nearly became a false accusation: the inbox's return value. `InboxSemanticsTest`
exists because that hypothesis had to be checked against the library before being reported — it was
wrong, and the library is correct.

## Three traps in testing this, all of which produce green-for-the-wrong-reason

1. **`@ServiceConnection` does not override the property.** A Kafka container contributes a
   `KafkaConnectionDetails` bean; `spring.kafka.bootstrap-servers` still reads whatever
   `application.yaml` says. A test consumer that trusts the property dials an address with nothing
   behind it and is never assigned a partition — so every "nothing was published" assertion passes.
2. **A subscription is not an assignment.** Produce before the group has joined and the record may or
   may not be seen: the same test passed and failed on consecutive runs.
   `ContainerTestUtils.waitForAssignment` in `@BeforeEach`, and an assertion that the assignment is
   non-empty, turn that into a loud failure.
3. **A topic is not a queue.** Records from earlier tests stay on it, so "there is a record on the
   DLT" passes because *another* test's record was dead-lettered. Every assertion here names the order
   it is about.

## Configuration worth reading

Both services list what the framework may create — being on the classpath is not being applied:

```yaml
aipersimmon.ddd.flyway.components: [outbox]          # ordering
aipersimmon.ddd.flyway.components: [inbox, outbox]   # inventory — see below
```

The inventory service carries an outbox it never writes to, because the Kafka starter's
durable-transport guard reads its subscription-declaring `@Externalized` as publication intent. Filed
as `docs/issue/issue-00161`; the pom says so at the dependency.

## Not demonstrated here yet

| | |
| --- | --- |
| Multi-tenancy end to end (S13) | Hosted here by the catalogue; not written yet. |
| One trace across the boundary (S15) | Same. |
| Both services booted against one broker | The two sides are tested against the wire contract independently; an end-to-end harness is the next increment. |
| Offset reset vs inbox retention | Documented in the companion; not yet a test — it needs a clock the inbox's retention respects. |
| Swapping Kafka for another broker | The seam is named in the companion (`OutboxDispatcher`); RabbitMQ/RocketMQ is not built. |
