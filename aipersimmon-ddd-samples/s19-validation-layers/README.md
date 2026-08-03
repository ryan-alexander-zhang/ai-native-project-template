# S19 — Three kinds of "not allowed"

The request's shape, a cross-context precondition, and an invariant of the aggregate are three
different things. The library gives each one its own mechanism, and the middle one exists to keep a
remote call from holding a database connection.

Companion document: `docs/analysis/analysis-00022-samples-validation-layers.md`.

## Run it

```bash
mvn -pl s19-validation-layers -am verify     # from aipersimmon-ddd-samples/
```

11 tests against a real PostgreSQL; they **skip** rather than fail without Docker.

## Code tour

| Layer | Where | Verified by |
| --- | --- | --- |
| The request's shape | `OrderController.PlaceOrderRequest` | `theRequestShapeIsRefusedBeforeAnythingIsBuiltFromIt` |
| The same shape, for every entry point | constraints on `PlaceOrder` | `thesameShapeIsCheckedAgainForEntriesThatAreNotHttp` |
| A cross-context precondition | `CustomerMustNotBeBlocked`, `WarehouseMustBeOpen` (`CommandPrecheck`) | `theprecheckRunsOutsideTheTransactionAndTheHandlerInsideIt` |
| The aggregate's own rule | `QuantityWithinCap` | `theAggregateRefusesWhatNoPrecheckScreened` |
| A handler with nothing left to validate | `PlaceOrderHandler` — three lines | all of them |

## The test to read

`theprecheckRunsOutsideTheTransactionAndTheHandlerInsideIt` does not argue from interceptor orders. The
two advisory ports are replaced by implementations that record
`TransactionSynchronizationManager.isActualTransactionActive()` **at the moment they are consulted**,
and a test-only interceptor at the innermost end records the same thing for the handler side:

```
precheck:customer-standing   insideTransaction = false
precheck:warehouse-calendar  insideTransaction = false
handler                      insideTransaction = true
```

That is why a precheck exists. Put the same query on the handler's first line and it runs inside the
write transaction — so the moment the port behind it is a remote client, a database connection sits idle
waiting on the network, and one slow dependency becomes an exhausted pool. No probe was added to
production code to show this; the observation point is the port implementation itself.

## Three things about prechecks

**All of them run, in bean order, and the first refusal wins.** With the customer blocked, the second
precheck is never asked. With two refusals both true, the client is told about the first — so `@Order`
is part of the contract, not decoration.

**They read and refuse, nothing else.** A write here would live outside the command's transaction and
survive its rollback. And they run on every dispatch including redeliveries, so they must be safe to
repeat.

**They are advisory, not a guarantee.** `PlaceOrderInternally` is a command type with no prechecks
registered against it — an operator tool, a migration — and the aggregate refuses an over-cap order
there just the same. That is the only one of the three layers that is a guarantee.

## Status codes follow the code, not the layer

| Raised by | code | category | Status |
| --- | --- | --- | --- |
| a precheck | `ordering.customer-blocked` | `FORBIDDEN` | 403 |
| a precheck | `ordering.warehouse-closed` | `CONFLICT` | 409 |
| the aggregate | `ordering.quantity-over-cap` | `DOMAIN_RULE` | 422 |

So the question when designing an error code is what the client should do about it, not which layer
threw it.

## Not demonstrated here, on purpose

| | Why |
| --- | --- |
| The error contract itself | Problem types, code naming, catalog overrides: S1. |
| Enforcing a rule that spans aggregates | A precheck is not enough; that needs an owner row (S8). |
| The port and ACL shape of a real cross-context call | S6. |
| Redelivery, the case prechecks must be repeat-safe for | S4 and S5. |
