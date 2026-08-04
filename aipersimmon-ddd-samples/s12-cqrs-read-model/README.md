# S12 — A read model no join could produce

The order list page shows the order, its status, and the product names. The names belong to another
bounded context, in another database. That is the whole reason this sample exists.

Companion document: `docs/analysis/analysis-00034-samples-cqrs-read-model.md`.

## Run it

```bash
mvn -pl s12-cqrs-read-model/catalog-service  -am verify    # 10 tests
mvn -pl s12-cqrs-read-model/ordering-service -am verify    # 28 tests
```

Real PostgreSQL and real Kafka via Testcontainers; they **skip** rather than fail without Docker. No
`docker-compose.yml`, following S4: the tests provide their own infrastructure and a compose file
would be a second, drifting description of it.

## When a projection is not the answer

**S20 is the counterexample, and it is the common case.** It serves a list endpoint straight from the
write model, with cursor paging done properly, and for a list of one context's own data that is
right — projection or not. Nothing about row counts changes that.

What forces a projection here is one column: `display_summary`, the product names. They live in the
catalogue's database, so:

| | |
| --- | --- |
| A SQL join | Impossible. Different database, different service. |
| A synchronous call per row | Couples every list render to the catalogue's uptime and latency, N times per page. |
| A copy, maintained by events | This sample. |

So the threshold is not "the query got slow", it is **"the query needs data this context does not
own"**. A slow query on your own tables is an index problem. Reach for a projection when the shape you
need spans an ownership boundary — or when the write model would have to be distorted to answer
cheaply, which is the same problem from the other side.

## Three tables, three lifecycles

Telling them apart is most of the design:

| Table | What it is | Who writes it | Can you delete it? |
| --- | --- | --- | --- |
| `s12_order`, `s12_order_line` | the write model — the truth | commands | no |
| `s12_product_name` | **this context's replica** of the catalogue's names | the rename listener | no — see below |
| `s12_order_list` | the projection | the projection, only | yes, any time |

The middle one is the one that gets left out, and leaving it out is what makes a projection
unrebuildable.

## Whose asset is a cross-context copy?

The catalogue owns product names. The ordering service **keeps a copy of the ones it displays**, in its
own table, fed by published events, and serves it. That copy is the ordering service's asset: it can be
queried, backed up, reasoned about and rebuilt with the catalogue switched off.

Three lines that follow from that, and they are the answer to the catalogue's ownership question:

- The ordering service may **not** read the catalogue's database. It subscribes.
- No third context may read `s12_product_name`. It is not a shared cache; it is one consumer's
  materialised subscription. A service that reads it without subscribing has found a back door with a
  schema.
- The catalogue must **not** know who copies its names. An ArchUnit rule enforces the direction. A
  publisher that knows its consumers starts serving them — an endpoint "for the order list", an export
  shaped like someone else's screen — and ownership of the copy silently moves back to where it cannot
  be maintained.

## The same value, two opposite requirements

This is the sharpest thing in the sample and it is one test:

| | Where | Requirement |
| --- | --- | --- |
| `s12_order_line.name_at_purchase` | the write model | **Never changes.** The invoice must say what the customer agreed to buy. |
| `s12_order_list.display_summary` | the projection | **Follows the catalogue.** The list should show what the product is called now. |

`thelistShowsTheNewNameAndTheInvoiceStillShowsTheOld` asserts both after a rename. Once you see it,
the general rule is hard to unsee: **a copied value is either a business fact or a display cache, and
which one it is decides the mechanism.** Getting it backwards produces either invoices that rewrite
themselves or list pages that never update.

## Rebuild, and the input that makes it possible

`POST /admin/order-list/rebuild` deletes the projection and rebuilds it. Six lines, and they are six
only because of two earlier choices:

1. **Whole-row recomputation, never deltas.** `OrderListWriter` has exactly one write method and it
   takes a complete row. So an event, a rename and a rebuild all go through
   `OrderListProjection.rebuild(orderId)` and produce identical bytes. A projection maintained by
   partial updates has no definition of a correct row to compare against, and its rebuild is a second
   implementation that must agree with the first.
2. **The replica.** Because the names are read from `s12_product_name` rather than copied from the wire
   into the projection row, every input a rebuild needs is a table this service owns.

`arebuildAfterARenameKeepsTheNewName` is the test that pays for point 2. Without the replica the
rebuild would produce the *frozen* name, and the only ways back would be re-asking the catalogue or
replaying its event history out of the broker's retention — a migration with an external dependency and
a deadline, not a rebuild. **A projection is rebuildable exactly when every input it needs is a table
you own.**

## Two clocks in one table

| Driven by | Transport | Lag | Asserted with |
| --- | --- | --- | --- |
| the order's own facts | in-process domain event, inside the writing transaction | none | direct assertions |
| the product's name | integration event over Kafka | measurable | `await()` |

A list row can be simultaneously current about the order and behind about the name. That is not a
defect: it is what "the name belongs to another context" means. Hiding it would only be guessing on the
customer's behalf, which is why `projectedAt` is a column and is returned to the caller.

## What the transaction phase does and does not buy

The projection uses `@EventListener`, not `@TransactionalEventListener`. The obvious explanation is
wrong, and the negative control is what said so.

**Measured:** switching both handlers to `AFTER_COMMIT` leaves every test in this service green except
one. Read-your-own-writes survives, because the command bus opens the transaction and nothing wraps it,
so an after-commit listener still runs synchronously before `send` returns. What buys
read-your-own-writes is the projection being **in process** — not the phase.

What the phase decides is which way a failure goes:

| | in the transaction (here) | `AFTER_COMMIT` |
| --- | --- | --- |
| the projection throws | the order is not placed | the order commits, the row is silently missing |
| a projection bug is | an outage on ordering | invisible drift |

So: share the transaction when a missing row would be worse than a failed write; do not when the
projection is expensive, remote or optional. Here it is two local statements feeding the screen the
customer lands on, so refusing the write is the better failure. `apoisonedProjectionTakesTheWriteDownWithIt`
is the only test in the service that can tell the difference.

## What a rename costs

`RecordProductNameHandler` returns the number of rows it recomputed, and the number is the point. One
rename is one replica write plus **one recomputation per order that ever contained that sku** —
unbounded, and growing for as long as the product sells.

That is the bill for denormalising another context's data: query load leaves the read path and
reappears, amplified, on the write path of an event whose rate you do not control. Three ways to bound
it, each a different sample: recompute only what someone is likely to look at, recompute lazily against
a version marker, or accept the stale name until the next rebuild. "Just project it" is not free.

## Code tour

| Concern | Where | Verified by |
| --- | --- | --- |
| What a projection row contains | `OrderListProjection` | `OrderListProjectionTest` (6, no DB, no Spring) |
| Read-your-own-writes | `@EventListener` + in-process | `thelistShowsAnOrderTheInstantItIsPlaced` |
| Which way a failure propagates | the same annotation | `apoisonedProjectionTakesTheWriteDownWithIt` |
| Frozen fact vs maintained display | `Order.Line.nameAtPurchase` vs `display_summary` | `thelistShowsTheNewNameAndTheInvoiceStillShowsTheOld` |
| A name this context has not heard of | the sku fallback | 2 tests, one at each layer |
| The replica as this context's asset | `ProductNames` + `s12_product_name` | `arebuildAfterARenameKeepsTheNewName` |
| Rebuild from local inputs only | `RebuildOrderListHandler` | 2 tests |
| Whole rows, never deltas | `OrderListWriter` | `arebuildIsIdempotentDownToTheRow` |
| The cross-context lag | Kafka + inbox | `RenameOverTheWireTest` (2) |
| What the inbox saves when correctness did not need it | `projected_at` unchanged | `aredeliveredRenameDoesNotRecomputeAnything` |
| Publishing the value, not a nudge | `ProductRenamed` | `RenamePublicationTest` |
| A no-op rename announcing nothing | `Product.renameTo` | 2 tests |
| Ownership direction | ArchUnit | 4 rules across both services |

## Five negative controls, each run on its own

| Change | Red | What it measured |
| --- | --- | --- |
| Projection moved to `AFTER_COMMIT` | exactly 1 | **The first run of this control produced zero red**, which is how the claim above got corrected: read-your-own-writes does not depend on the phase, failure coupling does. The test that now catches it was written because of it. |
| Rename no longer re-projects affected rows | 4 (2 by `await` timeout) | what the amplification buys — without it the list keeps the old name until a rebuild |
| Unknown-sku fallback removed | exactly 3 | caught first by the unit test; a null in the summary rather than a placeholder |
| `renameTo` always reports a change | exactly 3 | a same-name rename broadcasts, and every consumer recomputes for nothing |
| The projection reaches for the aggregate's port | exactly 1 (ArchUnit) | the rule is not vacuous |

## Not demonstrated here

| | |
| --- | --- |
| Paging the projection | S20's subject, and unchanged by where the rows come from. This query is a limit and one ordering. |
| Caching in front of the read | S26, deliberately paired with this one: a projection and a cache solve overlapping problems with different failure modes. |
| Online rebuild | This one runs in a transaction, deletes everything and writes it back, so the list is briefly empty. At scale you build into a second table and swap a view — S23's territory. |
| Projection schema evolution | Adding a column means a rebuild, which this sample makes cheap; the migration mechanics belong with S23. |
| A projection in its own database | Everything here is one datasource, so the projection commits with the write. Move it and you lose that and gain independent scaling — a real choice, and one the sample cannot make honestly without a second store. |
| Event-sourced projections | The rebuild reads the write model, not an event log. Rebuilding from events is a different architecture with a different retention problem. |
