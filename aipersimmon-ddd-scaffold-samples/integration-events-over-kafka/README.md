# How-to: integration events over Kafka

A focused, runnable example of the message-broker delivery mode on the AiPersimmon
DDD starters, and nothing else.

## What it shows

The full producer-to-consumer loop for an integration event across a broker:

1. **Publish through the outbox** — `ReservationService` places a reservation and
   publishes `ReservationPlaced` through the `IntegrationEvents` port in the same
   transaction. Backed by `aipersimmon-ddd-outbox-jdbc`, that writes an outbox row
   atomically with the business change.

2. **Relay to Kafka** — the outbox relay dispatches unsent rows through the
   `KafkaOutboxDispatcher` (`aipersimmon-ddd-messaging-kafka`), publishing each to a
   Kafka topic and marking the row sent only after the broker acknowledges
   (at-least-once).

3. **Consume, deduplicate, apply** — the messaging starter's consumer bridge
   (enabled with `aipersimmon.ddd.messaging.kafka.consumer.enabled=true`) reads from
   the topic, deduplicates on the event id via the inbox
   (`aipersimmon-ddd-inbox-jdbc`), and republishes the event in process, where
   `ReservationView` applies it to a read view as an ordinary `@EventListener`.

## Run it

```
mvn test
```

The test runs against an **embedded Kafka broker** and an in-memory H2 database, so
no external infrastructure is needed. It places a reservation and waits for the read
view to be updated, asserting the event travels outbox → Kafka → inbox → in-process
handler and is applied exactly once.

## Configuration

`src/main/resources/application.properties` selects the topic, turns on the consumer
bridge, and configures string (de)serializers — the transport carries the JSON
payload as the record value with the envelope metadata in headers. Point
`spring.kafka.bootstrap-servers` at your broker to run outside the test.
