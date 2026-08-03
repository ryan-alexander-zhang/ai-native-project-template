# S8 — Local transactions, aggregate boundaries and the optimistic lock

Where the transaction comes from, what the version predicate protects, and — the part most teams miss
— where it protects nothing at all.

Companion document: `docs/analysis/analysis-00021-samples-local-transaction-aggregate.md`.

## Run it

```bash
mvn -pl s08-local-transaction-aggregate -am verify    # from aipersimmon-ddd-samples/
```

12 tests against a real PostgreSQL; they **skip** rather than fail without Docker. `docker compose up`
is only for poking at the schema by hand.

## Code tour

| Concern | Where | Verified by |
| --- | --- | --- |
| The bus opens the transaction; no `@Transactional` anywhere | `ReserveStockHandler` | `TransactionBoundaryTest` |
| Two aggregates, one transaction, deliberately | same, with the reasoning in its javadoc | `whenTheSecondSkuIsRefusedTheFirstIsNotWrittenEither` |
| Decide everything before writing anything | load-all-then-save-all | same |
| Two lines naming one sku must be merged | `merge(...)` | `twoLinesNamingOneSkuAreOneReservation` |
| A lost write really is lost | the version predicate | `awriteThatLostTheRaceAffectsNoRow` |
| An update conflict is retried | `retry-on-conflict.enabled: true` | `aconflictIsTranslatedAndReplayedByTheRetryInterceptor` |
| A create conflict is **not** | the two exception types | `acreateThatCollidedIsNotReplayed` |
| Versions do not protect a rule that spans aggregates | — | `perAggregateVersionsDoNotProtectARuleThatSpansThem` |
| Giving that rule an owner row fixes it | `ReservationBudget` | `givingTheRuleAnOwnerRowMakesTheVersionProtectItAgain` |

## The two tests to read

**`perAggregateVersionsDoNotProtectARuleThatSpansThem`.** A transaction reads every stock row, adds up
what is reserved, checks `0 + 15 <= 20`, and passes. While it is still open, a second command reserves
15 units of a *different* sku on another thread and commits. The first transaction then saves its own
sku and hits **no conflict at all** — its row's version is exactly what it read. Both commands believed
they were inside a limit of 20; the total is 30.

Nothing is broken in the library. `version` is row-level optimistic concurrency control, not an
enforcer of cross-row invariants, and "I added optimistic locking" does not mean "my rule is safe".

**`givingTheRuleAnOwnerRowMakesTheVersionProtectItAgain`.** Same interleaving, but both commands also
debit `ReservationBudget` — one row, written by every reservation, so *its* version becomes the
serialisation point. The interleaving is refused and the limit holds.

The fix is not a wider lock. It is giving the rule an aggregate of its own.

Both interleavings are deterministic, and the second transaction has to run on another thread for a
real reason: a nested dispatch would join the first transaction rather than compete with it.

## Retry, and what must never be retried

Retry is off by default in the library and switched on here. `max-attempts` counts the first try, the
backoff doubles, and **each attempt is a fresh dispatch** — new transaction, aggregate reloaded,
prechecks re-run — which is why retrying is sound at all.

It replays `ConcurrencyConflictException` and nothing else. `DuplicateEntityException` is a separate
type on purpose: replaying a create that already happened either collides forever or creates a second
one. Do not switch retry on for a handler that has already caused an external side effect.

## Not demonstrated here, on purpose

| | Why |
| --- | --- |
| Pessimistic locks and higher isolation | Named in the document's escalation table; the sample shows the two ends (row version, owner row) rather than all five. |
| Cross-service consistency | Eventual is S4/S9, strong is S10. |
| Compensation | Undoing a step that already committed is S9. |
| The outbox sharing this transaction | S4. |
