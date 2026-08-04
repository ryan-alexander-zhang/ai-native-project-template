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
| S1 HTTP command and query | [s01-http-command-query](s01-http-command-query) | `analysis-00015` |
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

Ports: scenario N owns the block starting at `18000 + 10*N`, so several samples can run at once.

Test style is settled in **S18**: five layers, each assertion at the cheapest one that can answer it.
New samples follow it rather than inventing their own.
