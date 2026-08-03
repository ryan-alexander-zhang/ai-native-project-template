# S4 — One fact, two deployables

An order is placed in one service; stock is reserved in another. Between them: a transactional
outbox, a real Kafka broker, and an inbox that turns at-least-once delivery into at-most-once effect.

Two cross-cutting scenarios are hosted in this same code rather than in directories of their own:
**S13 multi-tenancy end to end** and **S15 following one request across the boundary**. Both are about
metadata that has to survive every hop, so they belong in the sample that has the hops.

Companion documents:

| | |
| --- | --- |
| S4 — outbox, Kafka, inbox | `docs/analysis/analysis-00025-samples-integration-events-across-services.md` |
| S13 — multi-tenancy end to end | `docs/analysis/analysis-00027-samples-multi-tenancy-end-to-end.md` |
| S15 — one request across the boundary | `docs/analysis/analysis-00028-samples-one-trace-across-the-boundary.md` |

## Run it

```bash
mvn -pl s04-integration-events-across-services/ordering-service   -am verify   # 20 tests
mvn -pl s04-integration-events-across-services/inventory-service  -am verify   # 18 tests
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

## S13 — the tenant, and the code that never mentions it

Tenancy is on in both services. No controller, command, handler, aggregate, repository or row class
mentions a tenant: the edge filter and the message consumer bind it, and the SQL is rewritten to carry
it. A tenant that travels as a method parameter is a tenant some method will eventually forget to pass.

| Claim | Test |
| --- | --- |
| Stamped on every row — root, child, outbox — with nothing in the code naming it | `theTenantIsStampedOnEveryRowWithoutAnyCodeMentioningIt` |
| **A foreign tenant's order id reads as 404, not 403** | `aforeignTenantsOrderIdReadsAsNotFound` |
| A request that resolves no tenant is rejected at the edge | `arequestThatResolvesNoTenantIsRejectedAtTheEdge` |
| The relay is *not* tenant-scoped and drains every tenant in one poll | `therelayIsNotTenantScopedAndDrainsEveryTenant` |
| A tenant-less thread fails closed instead of reading the sentinel bucket | `aTenantLessThreadFailsClosedRatherThanReadingTheSentinelBucket`, `theSamePortOnATenantLessThreadFailsClosed` |
| **`__root__` is a bucket, not a wildcard** | `theRootSentinelIsABucketNotAWildcard` |
| An unregistered tenant-carrying table is refused at startup | `anUnregisteredTenantCarryingTableIsRefusedByTheStartupGuard` |
| The tenant on the record decides which bucket moves | `thetenantOnTheRecordDecidesWhichBucketMoves` |
| A consumer has no request, so the tenant arrives on the message | `thecommandInheritedTheTenantFromTheEnvelopeAndNotFromAnyRequest` |
| A record carrying no tenant is dead-lettered, not attributed | `arecordCarryingNoTenantIsRejectedRatherThanAttributed` |
| One URL, one method, no tenant parameter — two answers | `thehttpReadIsScopedToTheCallersTenantWithoutAskingForIt` |
| The inbox dedup key deliberately excludes the tenant | `thededupKeyDeliberatelyExcludesTheTenant` |

Two things measured rather than assumed. **The allow-list fails open**: an unregistered table gets no
predicate on any statement, which is why the library checks the list against the live schema at
startup. And **isolation belongs in the key, not only in the predicate** — dropping `s04_stock` from
`tenant-tables` (with the guard off, so it could boot) does not leak here, it *stalls*: `selectById`
falls back to `WHERE sku = ?`, matches two rows, and `TooManyResultsException` is retried forever. Loud
— and loud only because the key is `(tenant_id, sku)`. With a single-column key the same mistake reads
somebody else's row and reserves from it.

## S15 — what actually connects the seven hops

Two answers, and they are not the same answer.

| | across HTTP | across the outbox | across the broker |
| --- | --- | --- | --- |
| trace | one trace (parent/child) | **a new trace, linked back** | one trace (the consumer resumes the record's) |
| correlationId | same value | same value | same value |

The catalogue called this "one complete trace". It is not one trace, and it should not be: the outbox is
a deliberate break in time, so the dispatch span **links** to the creating span instead of continuing
it — a child would make the trace's duration "how long the row waited". The library's own
`ConnectedTraceEndToEndTest` asserts exactly that. The identifier that *is* byte-identical end to end is
the correlation id, and it needs no backend at all.

| Claim | Test |
| --- | --- |
| The durable row carries the trace context of the request that wrote it | `theDurableRowCarriesTheTraceContextOfTheRequestThatWroteIt` |
| **The wire leaves under its own trace, linked back** | `theWireLeavesUnderItsOwnTraceLinkedBackRatherThanContinuingTheRequests` |
| The correlation id is identical on command, row and wire; causation advances | `theCorrelationIdIsTheOneIdentifierThatIsIdenticalEndToEnd` |
| Four MDC ids from four different modules | `theLogLineCarriesFourIdsFromFourDifferentModules` |
| The consumer joins the trace the record arrived on, with no code reading the header | `theWorkThisServiceDoesJoinsTheTraceTheRecordArrivedOn` |
| The correlation id crosses the broker unchanged; causation advances again | `thecorrelationIdCrossesTheBrokerUnchangedAndTheCausationChainAdvances` |
| **A consumer's log line carries one of the four ids, not three** | `aconsumersLogLineCarriesOneOfTheFourIdsAndNotThreeOfThem` |

Two gaps worth knowing before an operator hits them. The caller-facing `X-Request-Id` and the messaging
`correlationId` are **different ids with no bridge in the data** — a root command's correlation id is
its own message id. And on the consuming side three of the four MDC keys are absent, because they are
written by servlet filters and a consumer has no request: the tenant *is* bound and the trace *is*
joined, so it is a logging gap, not a propagation failure.

The `traceparent`/`trace_state` columns exist on the outbox row without the observability starter and
stay NULL: capture goes through an SPI whose default captures nothing. Being on the schema is not being
filled in.

## Not demonstrated here yet

| | |
| --- | --- |
| Both services booted against one broker | The two sides are tested against the wire contract independently; an end-to-end harness is the next increment. |
| A real tracing backend | Exporters are `none` — a sample has no collector. What the two hops need is a column and a header, and neither needs a backend to assert. |
| Tenant migration and merge | A data operation, and one that must go through the cross-tenant path §4.1 of the S13 document warns about. |
| Database-per-tenant / schema-per-tenant | Only the pool-with-a-discriminator shape is shown. |
| Metrics, alerting, dead-letter replay | S22. |
| Offset reset vs inbox retention | Documented in the companion; not yet a test — it needs a clock the inbox's retention respects. |
| Swapping Kafka for another broker | The seam is named in the companion (`OutboxDispatcher`); RabbitMQ/RocketMQ is not built. |
