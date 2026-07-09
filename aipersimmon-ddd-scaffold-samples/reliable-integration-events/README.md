# How-to: reliable integration events (outbox + inbox)

A focused, runnable example of two reliability patterns from the AiPersimmon DDD
starters, and nothing else.

## What it shows

- **Transactional outbox (`aipersimmon-ddd-outbox-jdbc`)** — publishing an
  integration event through the `IntegrationEvents` port writes it into the
  outbox table *in the same transaction* as the business change. If the
  transaction rolls back, the event row rolls back with it: there is no
  dual-write where the database change commits but the message is lost (or vice
  versa). A scheduled relay then dispatches unsent rows and marks them sent
  (at-least-once delivery).

- **In-process asynchronous delivery** — setting
  `aipersimmon.ddd.outbox.dispatch=in-process` makes the relay republish each
  stored event to local `@EventListener` handlers instead of only logging. You get
  outbox reliability (the event waits durably in the table) with in-process
  handling and no broker.

- **Idempotent inbox (`aipersimmon-ddd-inbox-jdbc`)** — guarding a consumer with
  the `Inbox` port makes it converge under redelivery: handling the same message
  key twice applies the effect once.

## Run it

```
mvn test
```

Two tests drive the patterns against an in-memory H2 database, so no broker or
external database is needed. `ReliableIntegrationEventsHowToTest` asserts: the
outbox row is written with the business change; a failure rolls back both; the
relay dispatches and marks rows sent; and a duplicate delivery is skipped.
`InProcessDeliveryHowToTest` enables in-process dispatch and asserts the relay
delivers the event to the local listener, which applies it once.
