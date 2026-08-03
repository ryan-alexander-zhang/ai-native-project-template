# S20 — The read side has a contract too

Three paging shapes ship in the library and none of them was shown in use. This is the list endpoint
written out: which shape to pick, what goes inside the cursor, why the ordering has to be total, and
what offset paging quietly loses.

Companion document: `docs/analysis/analysis-00023-samples-query-contract-paging.md`.

## Run it

```bash
mvn -pl s20-query-contract-paging -am verify    # from aipersimmon-ddd-samples/
```

25 tests: 8 unit (no Spring, no database) and 14 against a real PostgreSQL, which **skip** rather
than fail without Docker. `docker compose up` is only for poking at the schema by hand.

## Code tour

| Concern | Where | Verified by |
| --- | --- | --- |
| `Slice` by default, `Page` where a human reads the number | `/orders` vs `/admin/orders` | `aSliceIsOneStatementAndTotalsCostASecond` |
| The request as a value, size bound included | `PageRequest` | `apageSizeBeyondTheCeilingIsRefusedAtEveryEntry` |
| What is inside a cursor, and the only code that opens one | `PageCursor` | `PageCursorTest` (8 tests) |
| The seek predicate as a row-value comparison | `MyBatisOrderQueries.SEEK_BACKWARD` | `walkingEveryPageVisitsEveryRowExactlyOnce` |
| Ordering as a closed set, not a client-supplied column | `OrderSort` | `anUnknownOrderingNeverReachesAStatement` |
| Filters as components, not string building | `OrderFilter` + conditional `eq` | `filtersComposeWithoutAnySqlBeingBuiltByHand` |
| What offset paging loses | `OffsetPager` (a counterexample) | `aRowLeavingTheSetMakesTheOffsetPagerSkipAnother` |
| The pagination interceptor, contributed at the reserved order | `PagingConfig` | the whole suite |

## The two tests to read

**`aRowLeavingTheSetMakesTheOffsetPagerSkipAnother`.** Six open orders, three per page. The client
reads page 1; one of the orders it just saw is confirmed and leaves the filter; the client asks for
page 2. `OFFSET 3` now points past the row that moved into position 3 — an order that is still open,
still matches the filter, and that this client will never be shown. Nothing errors and nothing logs.

**`theKeysetPagerSkipsNothingWhenARowLeavesTheSet`.** The same interleaving through the cursor. The
token names a row rather than a count, so a row leaving the set above it changes nothing about where
"after this one" is: every remaining row appears exactly once.

The counterexample differs from the real pager in exactly one respect, so the comparison isolates one
variable: same filter, same ordering, same rows.

## What the negative controls showed

Two deliberate breakages, each reverted:

| Break | Effect |
| --- | --- |
| `hasMore = rows.size() >= size` (drop the `+ 1` trick) | exactly one test red: the full last page hands out a cursor to an empty page |
| the seek predicate removed | four tests red — and before a guard was added, the suite **hung** |

The second one is the finding worth keeping. A broken cursor does not return a wrong page; it returns
the same page for ever, and the export job that reads the whole list never finishes. `walk(...)` now
bounds itself and fails with *"pagination did not terminate: the cursor is not advancing"* in
milliseconds, which is what the incident actually looks like.

## Choosing a shape

| | Cost | Use when |
| --- | --- | --- |
| `Slice` | one statement, at most `size + 1` rows | almost always — feeds, lists, exports, anything a machine reads |
| `Page` | a second statement that scans every match | a human reads the total, and the filtered set is small enough to count |

`Page` is `Slice` plus totals, not a different way of paging: it still carries a cursor, never a page
number. So moving from one to the other never changes how a client asks for the next page.

## Four things about the cursor

**It carries the whole sort key**, not just the id. The seek predicate compares against the key it
ordered by.

**It carries which question it answers** — the ordering, and a digest of the filter. Replayed against
a different filter it describes a position in a result set that does not exist; the sample refuses it
with a 400 rather than returning a page that is neither the first nor the next.

**The digest is not `hashCode()`.** A record's hash is stable within one JVM run and no further, and a
cursor outlives a deploy. `OrderFilter.fingerprint()` is a SHA-256 of the canonical form, and a test
pins the value — because changing it would refuse every cursor the previous release issued.

**Opaque is not encrypted.** Base64url keeps clients from parsing it, which is what buys the freedom
to change the format. It buys no confidentiality: nothing secret goes in, and a token that must not
be forgeable needs a signature (S2).

## Two library facts this sample leans on

**The query bus ships no interceptors.** The command bus validates every command it dispatches; the
query side has no such gate, so a read contract lives in the query type itself or nowhere. That is why
the size ceiling is in `PageRequest` and not only on the HTTP parameter.

**Time-ordered ids are not a licence to sort by id.** `IdGenerator`'s contract says the value is
opaque — "callers must not parse it or depend on the embedded timestamp being present". So an ordering
the business cares about needs a column it owns (`placed_at`), and the id's job is to break ties, which
it can do because it is unique and immutable. Sorting by id alone is sorting by "roughly when, if the
default generator is installed".

## Not demonstrated here, on purpose

| | Why |
| --- | --- |
| A projection maintained from events | This reads the write tables directly, which is right until it is not: S12. |
| Deep-page performance measured | The index and the predicate are here; measuring belongs with the rest of operability (S22). |
| `explain` output for the keyset index | Would need a data volume this sample deliberately does not create. |
| Filtering by tenant | Automatic and orthogonal — the tenant line rewrites reads too (S13, hosted in S4). |
| Signed cursors | A cursor is a position, not a capability; signing request bodies is S2. |
