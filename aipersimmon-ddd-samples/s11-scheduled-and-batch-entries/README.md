# S11 — Entries that are not HTTP

A timer, a batch and an operator, all reaching the domain through the same command channel as a
request. The example is the one every system has: close orders nobody paid for.

Companion document: `docs/analysis/analysis-00024-samples-scheduled-and-batch-entries.md`.

## Run it

```bash
mvn -pl s11-scheduled-and-batch-entries -am verify    # from aipersimmon-ddd-samples/
```

17 tests against a real PostgreSQL; they **skip** rather than fail without Docker. `docker compose up`
is only for poking at the schema by hand.

## The four entry shapes

| Shape | Here | In full |
| --- | --- | --- |
| HTTP request | `OrderController` | S1, S2 |
| Passage of time | `ExpiredOrderSweepScheduler` | this sample |
| A batch over many aggregates | `ExpiredOrderSweep` | this sample |
| An operator, on purpose | `OperationsController` | this sample |
| An inbound message | — | S4, S5 |
| A partner's callback | — | S7 |

They differ only in what arrives. None of them holds a rule, and an ArchUnit rule here forbids any of
them from touching the persistence tier — the pressure to break that is highest exactly at a timer,
where "just run one `UPDATE` from the scheduler" is a two-line change.

## Code tour

| Concern | Where | Verified by |
| --- | --- | --- |
| Find candidates, then send one command each | `ExpiredOrderSweep.sweepOnce` | `thesweepSendsOneCommandPerOrderRatherThanOneStatementForAll` |
| What the bulk `UPDATE` loses | `BulkCloser` (a counterexample) | `thebulkStatementClosesThePaidOrderTooAndTellsNobody` |
| The scan is advisory; the aggregate decides | `ExpiredOrders`, `Order.close` | `anorderPaidBetweenTheScanAndItsCommandCountsAsSkippedNotFailed` |
| Failure is per order, not per round | one command = one transaction | `onefailingOrderDoesNotRollBackTheOthers` |
| Retry with no bookkeeping | the candidate query | `thenextRoundRetriesWhatFailedAndNothingElse` |
| A bounded round | `ordering.sweep.batch-size` | `aroundIsBoundedAndTheBacklogDrainsOverRounds` |
| One correlation id per round | `CommandContext.root` + `send(cmd, cause)` | `everyCommandOfOneRoundSharesOneCorrelationId` |
| The tenant a timer thread does not have | `TenantContext.effective` / `runAs` | two tenant tests |
| Two instances, no lock | the version-checked transition | `twoInstancesSweepingAtOnceCloseEachOrderExactlyOnce` |
| The trigger actually fires | `ExpiredOrderSweepScheduler` | `ScheduleTest` |

## The test to read

**`twoInstancesSweepingAtOnceCloseEachOrderExactlyOnce`.** A second instance runs a whole round from
the moment this one's first command reaches the bus (on another thread, because a nested dispatch would
join this transaction instead of competing with it). Result: every order closed exactly once, and the
losing round reports three skips.

There is no lock, no lease and no claim table. Closing an order is a version-checked state transition,
so the row itself arbitrates — the aggregate already had what was needed (S8).

**That reasoning has a boundary.** It holds because the unit of work is a state change on a row that
carries a version. Work with nothing to version — "send a reminder", "call a partner API" — has nothing
to arbitrate two instances, and then you must claim the work before doing it, with a lease that
expires. The library's outbox relay is the reference for that shape, and its scheduler says why the
obvious alternative is worse:

> Guarding the schedule with a lock instead would put delivery behind a single holder — and an instance
> killed while holding it releases nothing, so every other instance would skip its poll, silently,
> until that lock expired.

So: every instance runs the schedule; the *work* is what gets made exclusive.

## What the negative controls showed

Three deliberate breakages, all reverted:

| Break | Effect |
| --- | --- |
| remove `TRANSITIONS.check` from `close()` | two tests red — the paid order gets closed, and both instances close every order |
| `@Transactional` on `sweepOnce()` | four tests red with `UnexpectedRollbackException` |
| remove `ORDER BY payment_due_at` from the scan | **nothing red** |

The second is worse than "the successes roll back". The loop still counts three closures and returns a
report saying so — and then the commit throws, because one joined transaction was marked rollback-only
by the refusal inside it. A round that reports success and then loses everything is the failure mode of
batching a batch into one transaction.

The third is a finding about the test, not the code. PostgreSQL serves the scan from the index on
`(status, payment_due_at)`, whose scan order happens to be the order the test asserts, so the assertion
documents the intent without being able to enforce it. The `ORDER BY` stays, because a plan is not a
contract: change the index, add a filter, upgrade the planner, and an unordered scan starts starving a
backlog's tail with no test to notice.

## Outcomes a batch must distinguish

`SweepReport` has three, and conflating any two of them is how a scheduled job becomes unobservable:

| | Means | Who cares |
| --- | --- | --- |
| `closed` | the command committed | the dashboard |
| `skipped` | the aggregate refused — paid, already closed, gone, or another instance got there first | nobody; this is an advisory scan working correctly |
| `failures` | something actually broke | whoever gets paged |

A job that reports skips as failures trains its operators to ignore failures. A job that reports
failures as nothing at all is the one that has been broken for six weeks.

## Not demonstrated here, on purpose

| | Why |
| --- | --- |
| Claiming work with a lease | The library's outbox relay already is that reference implementation; duplicating it here would teach it as the default, and for a versioned state change it is not. |
| A distributed lock (ShedLock and friends) | Named above with the reason it is usually the wrong trade. Nothing in the library asks for one. |
| Multi-tenant sweeping end to end | The tenant plumbing is shown (`effective`, `runAs`); the tenant column, the line interceptor and propagation are S13, hosted in S4. |
| Authorization on the operator entry | This sample has no security tier, and a fake role check would teach the wrong thing. |
| An inbound message as a trigger | S4, S5 — including why redelivery makes repeat-safety a requirement rather than a nicety. |
| Chunked/parallel batch processing | The bounded round is here; parallelism across rounds needs the claim shape above, and the volume to justify it. |
