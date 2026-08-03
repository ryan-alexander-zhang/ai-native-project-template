# S18 — Testing strategy

The same order slice, tested five ways, so that each assertion sits at the cheapest layer that can
answer it. This is the sample the other samples copy their test style from.

Companion document: `docs/analysis/analysis-00019-samples-testing-strategy.md`.

## Run it

```bash
mvn -pl s18-testing-strategy -am verify     # from aipersimmon-ddd-samples/
```

22 tests. Only three of them need Docker; they **skip** rather than fail without it.

## The five layers

| Layer | Test | Answers | Cost |
| --- | --- | --- | --- |
| 0 Architecture | `ArchitectureTest`, `PackageInfoTest` | layering, building blocks, the version-witness fence | under a second, over code nobody exercised |
| 1 Domain | `OrderTest` | invariants, transitions | milliseconds, no framework |
| 2 Application | `HandlerWithDoublesTest`, `DispatchingSubscriberTest` | what was saved, announced, refused, dispatched | milliseconds, on the library's doubles |
| 3 Edge slice | `OrderControllerSliceTest` | body → command, and the problem contract | ~1s, no container |
| 4 Integration | `OrderPersistenceTest` | the SQL, the version predicate, the real wiring | seconds, one container |

The distribution is 5 / 3 / 8 / 2 / 3 — most assertions at the cheapest layers, the container spent
only on what a double cannot answer.

## The library's doubles

| Double | Replaces | Used by |
| --- | --- | --- |
| `RecordingIntegrationEvents` | `IntegrationEvents` | `HandlerWithDoublesTest` |
| `RecordingCommandBus` | `CommandBus` | `DispatchingSubscriberTest` |
| `@WithTenant("acme")` | the ambient tenant | `DispatchingSubscriberTest` |
| `InMemoryInbox`, `ImmediateUnitOfWork` | `Inbox`, `UnitOfWork` | **not here** — this sample has no consumer for either; see the document |

`RecordingIntegrationEvents` builds a real `EventEnvelope`, so a test asserts what a consumer would
receive — and an event class missing `@EventType` fails there rather than in production. The one double
the library does not ship is a repository fake, because a repository port is your own vocabulary; keep
it as dumb as `InMemoryOrders` here.

## Two things found by writing these tests

**A slice test does not include the library's exception advice.** `@WebMvcTest` loads only web-related
auto-configurations, so without the explicit `@ImportAutoConfiguration` in
`OrderControllerSliceTest` the 400 response has no problem body at all — `$.type` resolves to nothing.
A test that only asserts the status code passes while verifying none of the error contract.

**A real context runs subscribers your test never mentions.** `theVersionPredicateIsReallyThere` first
used an amount of 100 and failed with `illegal state transition: CONFIRMED -> CONFIRMED`: below the
threshold, `AutoConfirmSmallOrders` woke on the domain event and confirmed the order. That is not a bug,
it is the difference between the two layers — a double does only what the test wired — so the sample
keeps it as an assertion (`aSmallOrderIsAutoConfirmedByASubscriberNobodyCalled`) and moved the
concurrency test above the threshold so it tests one thing.

## Where each assertion belongs

Ask three questions and stop at the first yes:

1. Only domain state and rules? → layer 1.
2. Only which port the use case called? → layer 2, with a double.
3. Needs real SQL, real wiring or real concurrency? → layer 4.

Everything else is edge translation, which is layer 3. Repeating a layer-1 or layer-2 assertion in
layer 4 is the most common source of slow builds.

## Not demonstrated here, on purpose

| | Why |
| --- | --- |
| Waiting on async chains | Never `Thread.sleep`; poll for the condition. There is no async link here — real examples are S4 and S9. |
| Contract tests across revisions | S21. |
| A full end-to-end test | S1 has one; one or two per service is usually enough. |
