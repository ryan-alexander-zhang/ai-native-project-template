# S17 — Mapping an aggregate to tables

The library gives you the write path and leaves the mapping entirely to you. This is where teams
adopting it actually bleed, so every claim here has a test.

Companion document: `docs/analysis/analysis-00018-samples-aggregate-persistence-mapping.md`.

## Run it

```bash
mvn -pl s17-aggregate-persistence-mapping -am verify    # from aipersimmon-ddd-samples/
```

No HTTP layer, and `docker compose` is only there for poking at the schema by hand: the tests drive a
real PostgreSQL through Testcontainers. They **skip** rather than fail without Docker.

## Code tour

| Concern | Where | Verified by |
| --- | --- | --- |
| Round-trip: root, children, both kinds of value object | `MyBatisOrders` | `aSavedAggregateComesBackWhole` |
| A field the domain emptied really becomes NULL | the base class, via `ClearedColumns` | `anEmptiedColumnActuallyReachesTheDatabase` |
| …and what the usual `updateById` loses instead | `NaiveOrderWriter` (a deliberate counterexample) | `theNaiveWriterSilentlyKeepsTheOldValue` |
| Version 0 means insert, so a forgotten `restoreVersion` collides | `Order.reconstitute` | `anAggregateAtVersionZeroTakesTheInsertBranchAndCollides` |
| A stale aggregate loses the race | the version predicate | `aStaleAggregateLosesTheRace` |
| Root, children and events commit together or not at all | base-class guard | `savingOutsideATransactionIsRefused` |
| Children diffed by identity, not deleted and reinserted | `MyBatisOrders#saveChildren` | `childrenAreDiffedSoUntouchedLinesAreLeftAlone` |
| A read that needs no aggregate does not build one | — | `aReadThatDoesNotNeedTheAggregateDoesNotBuildOne` |

10 tests. Dependencies are picked module by module rather than from a bundle, because a persistence
sample has no use for the web tier.

## The three things this sample exists to show

**`updateById` is the wrong way to save an aggregate, and it fails quietly.** MyBatis-Plus leaves null
fields out of the `SET` clause — correct for a *partial* update, wrong here, because `toRow` maps the
whole root and a null means the aggregate emptied that field. `NaiveOrderWriter` does it the usual way
and differs from the real repository in exactly that one respect: the update reports one row, the
version moves from 1 to 2, events would publish — and the old value is still in the database.

**Version 0 means insert.** `restoreVersion` is `protected`, so only the aggregate's own rebuild
factory can carry the version back; forget it and an update becomes an insert on an id that already
exists. The library's exception names that exact mistake alongside the genuine race.

**Child-write strategy follows the model, not convenience.** These lines are entities, so the write
diffs by identity: the amended line keeps its id, the removed one goes, the untouched one is not
written at all. Delete-and-reinsert is simpler and right for a *value object* collection (S1 does
that), and wrong here.

## One thing that will bite anyone using a JSON column

MyBatis-Plus's own `JacksonTypeHandler` binds its JSON with `setString`, and PostgreSQL refuses to
assign that to `jsonb`:

```
column "shipping_address" is of type jsonb but expression is of type character varying
```

`JsonbTypeHandler` here is the least invasive fix — same JSON, bound as `Types.OTHER` so the server
infers the type. The alternatives are declaring the column `text` (losing every JSON operator and
index) or setting `stringtype=unspecified` on the connection (changing how every string parameter in
the application is bound, to fix one column). Also note `@TableName(autoResultMap = true)`: without it
the handler is used for writing and ignored for reading, and the value object loads as null.

## Not demonstrated here, on purpose

| | Why |
| --- | --- |
| Why the model looks like this | Aggregate boundaries, entity vs value object, the rule primitives: S16. |
| Transaction boundaries and conflict retry policy | Who opens the transaction and which commands may retry: S8. |
| A projection with its own store | Reading the write tables flat is shown; diverging far enough to need a second store is S12. |
| Schema evolution | Adding a column to a live aggregate, backfilling: S23. |
| Logic-delete columns | `ClearedColumns` skips them; soft delete as a whole is S27. |
