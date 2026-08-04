# aipersimmon-ddd samples

One runnable sample per DDD flow scenario. Each directory demonstrates the flow its companion
document describes; the scenario catalogue is
`docs/analysis/analysis-00014-ddd-samples-scenario-catalog.md`.

These samples are independent of `aipersimmon-ddd-scaffold`. They do not reuse it, do not follow its
choices, and are not constrained by it. The only thing they track is the real behaviour of the
`aipersimmon-ddd` library.

## Building

One reactor, on purpose: a sample that builds only when someone remembers to build it rots.

```bash
mvn verify                                  # every sample
mvn -pl s01-http-command-query -am verify   # one
```

Java 21, Spring Boot 3.5.x, MyBatis-Plus. The library must be installed first
(`mvn -f ../aipersimmon-ddd/pom.xml install`). Data access is MyBatis-Plus throughout; the JDBC
variants of the framework modules are not used.

## Samples

| Scenario | Directory | Document |
| --- | --- | --- |
| S1 HTTP command and query (hosts **S14** the operation log) | [s01-http-command-query](s01-http-command-query) | `analysis-00015`, `-00038` |
| S2 Idempotency and replay protection | [s02-http-idempotency](s02-http-idempotency) | `analysis-00017` |
| S3 Domain events, in process | [s03-domain-events-in-process](s03-domain-events-in-process) | `analysis-00020` |
| S4 Integration events across services (hosts **S13** tenancy and **S15** tracing) | [s04-integration-events-across-services](s04-integration-events-across-services) | `analysis-00025`, `-00027`, `-00028` |
| S5 Messages from a foreign system | [s05-external-messages-inbound](s05-external-messages-inbound) | `analysis-00029` |
| S6 Synchronous call between services | [s06-synchronous-call-between-services](s06-synchronous-call-between-services) | `analysis-00030` |
| S7 A third party, in both directions | [s07-third-party-integration](s07-third-party-integration) | `analysis-00031` |
| S8 Transactions and the optimistic lock | [s08-local-transaction-aggregate](s08-local-transaction-aggregate) | `analysis-00021` |
| S9 A flow that must converge, and compensation | [s09-eventual-consistency-process-manager](s09-eventual-consistency-process-manager) | `analysis-00032` |
| S10 One outcome across two databases (Seata AT and TCC) | [s10-strong-consistency-seata](s10-strong-consistency-seata) | `analysis-00033` |
| S11 Entries that are not HTTP | [s11-scheduled-and-batch-entries](s11-scheduled-and-batch-entries) | `analysis-00024` |
| S12 A read model no join could produce | [s12-cqrs-read-model](s12-cqrs-read-model) | `analysis-00034` |
| S16 Tactical modelling | [s16-tactical-modelling](s16-tactical-modelling) | `analysis-00016` |
| S17 Aggregate to tables | [s17-aggregate-persistence-mapping](s17-aggregate-persistence-mapping) | `analysis-00018` |
| S18 Testing strategy | [s18-testing-strategy](s18-testing-strategy) | `analysis-00019` |
| S19 Three kinds of "not allowed" | [s19-validation-layers](s19-validation-layers) | `analysis-00022` |
| S20 The read side's contract | [s20-query-contract-paging](s20-query-contract-paging) | `analysis-00023` |
| S21 Contract evolution and coexisting revisions | [s21-event-contract-evolution](s21-event-contract-evolution) | `analysis-00026` |
| S22 Dead letters, replay, retention, startup guards | [s22-operability-deadletters-retention](s22-operability-deadletters-retention) | `analysis-00035` |
| S23 Schema evolution and data migration | [s23-schema-migration](s23-schema-migration) | `analysis-00036` |
| S24 Adding a third bounded context, and keeping the option of splitting it out | [s24-add-bounded-context](s24-add-bounded-context) | `analysis-00041` |
| S26 A cache, a projection, and what each cannot do | [s26-read-side-caching](s26-read-side-caching) | `analysis-00037` |
| S27 Three things called delete, and a compliance erasure | [s27-soft-delete-and-erasure](s27-soft-delete-and-erasure) | `analysis-00039` |
| S28 Where the synchronous limit is, and what a job resource looks like | [s28-long-running-endpoints](s28-long-running-endpoints) | `analysis-00040` |

Ports: scenario N owns the block starting at `18000 + 10*N`, so several samples can run at once.

Three samples ship deliberate anti-patterns so their consequences can be measured rather than asserted.
**S26** has `CachedProducts`, which memoises the aggregate repository — four lines, passes every read-side
test, and makes a rename report success while writing nothing — in **test scope only**; and
`ProductCacheInvalidation.Eager`, which evicts inside the transaction, in main but reachable only by setting
`s26.cache.invalidate=IN_TRANSACTION` away from its default. **S27** has `HandRolledFlag`, a second row class
over the same table that maintains its delete flag without `@TableLogic`, also test scope only. **S28** has two:
`BufferedExport`, a four-line mapper returning `List<Row>` that passes every test written against fifty rows, in
test scope; and `ExportAsProcess`, a faithful modelling of the export as a durable process — also test scope,
because that is the shape the sample exists to warn about. Each has a sibling test showing the correct arrangement
behaves differently, because a failure demonstrated without its control is not a finding.

One anti-pattern is in main code and reachable only by asking for it: **S28**'s
`MyBatisProgressBoard.SameTransaction`, selected by `s28.export.progress-transaction=SAME_TRANSACTION`, publishes a
progress counter inside the export's own transaction — visible to its author's test, invisible to everybody else
until the work is over. Same convention as S26's `ProductCacheInvalidation.Eager`.

One sample configures its own broker rather than using the shared test-support container: **S22** runs
Kafka with topic auto-creation off, because the two failures it is about (publishing to an unprovisioned
topic, and a poison record with no `<topic>.DLT`) are both invisible when the broker creates topics on
demand. Every other sample runs against the permissive default.

Test style is settled in **S18**: five layers, each assertion at the cheapest one that can answer it.
New samples follow it rather than inventing their own.
