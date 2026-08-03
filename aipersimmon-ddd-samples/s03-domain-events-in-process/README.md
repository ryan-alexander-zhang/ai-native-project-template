# S3 — Domain events, in process

One command, two subscribers, two transaction phases — and a passing test showing what an in-process
domain event loses.

Companion document: `docs/analysis/analysis-00020-samples-domain-events-in-process.md`.

## Run it

```bash
mvn -pl s03-domain-events-in-process -am verify     # from aipersimmon-ddd-samples/
```

Or by hand:

```bash
docker compose up -d
mvn -pl s03-domain-events-in-process -am spring-boot:run

# a first order: the coupon is granted in the same transaction, the notification after commit
curl -sS -X POST localhost:18030/orders -H 'Content-Type: application/json' \
  -d '{"customerId":"customer-1","firstOrder":true,"amountCents":2500}'
```

Two customer-id prefixes are wired to fail on purpose: `poison-*` makes the in-transaction subscriber
throw, `unreachable-*` makes the after-commit one throw. The tests use both.

## Code tour

| Concern | Where | Verified by |
| --- | --- | --- |
| The aggregate records the fact | `Order.place` → `registerEvent` | `PublishGuardTest` |
| The **repository** drains and publishes it, not the handler | `MyBatisOrders#save`, and the absence of any publish call in `PlaceOrderHandler` | `PublishGuardTest.publishingDrainsTheAggregate` |
| A reaction that must share the transaction | `GrantWelcomeCoupon` (`@EventListener`) | `DomainEventPhaseTest` |
| A reaction that must not happen for an uncommitted write | `NotifyCustomer` (`@TransactionalEventListener(AFTER_COMMIT)`) | `DomainEventPhaseTest` |
| A subscriber reaching back into the aggregate is refused | the library's publish guard | `PublishGuardTest` |
| Subscribers live in the application layer and are annotated | `@DomainEventHandler` | `ArchitectureTest` |

9 tests. Only the phase test needs Docker; it **skips** rather than fails without it.

## The point of the sample

`afailingAfterCommitSubscriberLosesTheReactionAndKeepsTheWrite` is the test to read. The notifier
throws, and:

- the order and the coupon are **committed**;
- the notification is **gone** — nothing retried it, nothing recorded that it was owed;
- `commandBus.send(...)` threw **nothing**, so the caller never learns.

That is what "in-process domain events are volatile" means in practice. Its sibling
`afailingInTransactionSubscriberTakesTheOrderDownWithIt` shows the other phase: there the order is
never written at all.

So the rule is not "outbox is for crossing services". It is: **if losing the reaction has a business
consequence, an in-process event is the wrong mechanism — even inside one JVM.** A process that dies
between commit and the after-commit listener loses it just as thoroughly. S4 is how the outbox fixes
that.

## Choosing the phase

Ask: *if this reaction fails, should the write still exist?*

- **No** → `@EventListener`, inside the transaction. Granting the welcome coupon: no coupon, no order.
- **Yes** → `@TransactionalEventListener(AFTER_COMMIT)`. Telling a customer about an order that rolled
  back is worse than telling them nothing.

Getting it wrong produces no error, only a different outcome.

## Not demonstrated here, on purpose

| | Why |
| --- | --- |
| The outbox | This sample shows the loss; S4 shows the fix, with the transport and the consumer inbox. |
| Whether two aggregates in one transaction is acceptable | The phase decides *where* the second write lands; whether it belongs there is S8. |
| Integration events and their contracts | S4 and S21. |
| Waiting on async chains in tests | Everything here is synchronous by construction. |
