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

- **Idempotent inbox (`aipersimmon-ddd-inbox-jdbc`)** — guarding a consumer with
  the `Inbox` port makes it converge under redelivery: handling the same message
  key twice applies the effect once.

## Run it

```
mvn test
```

The test drives both patterns against an in-memory H2 database, so no broker or
external database is needed. `ReliableIntegrationEventsHowToTest` asserts:
the outbox row is written with the business change; a failure rolls back both;
the relay dispatches and marks rows sent; and a duplicate delivery is skipped.
