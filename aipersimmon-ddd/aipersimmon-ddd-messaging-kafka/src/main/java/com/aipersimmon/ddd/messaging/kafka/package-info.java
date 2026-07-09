/**
 * Kafka transport for integration events, layered on the transactional outbox.
 *
 * <p>{@link com.aipersimmon.ddd.messaging.kafka.KafkaOutboxDispatcher} is the
 * producer side: an {@link com.aipersimmon.ddd.outbox.jdbc.OutboxDispatcher} that
 * publishes each stored outbox row to a Kafka topic, carrying the envelope metadata
 * in {@link com.aipersimmon.ddd.messaging.kafka.IntegrationEventHeaders} and the
 * JSON payload as the record value; the outbox relay drives it, marking a row sent
 * only after the broker acknowledges.
 * {@link com.aipersimmon.ddd.messaging.kafka.KafkaIntegrationEventListener} is the
 * opt-in consumer side: it deduplicates on the event id through the
 * {@link com.aipersimmon.ddd.application.Inbox} and republishes the reconstructed
 * event in process for local {@code @EventListener} handlers.
 * {@link com.aipersimmon.ddd.messaging.kafka.AipersimmonDddMessagingKafkaAutoConfiguration}
 * wires both, configured by
 * {@link com.aipersimmon.ddd.messaging.kafka.KafkaMessagingProperties}.
 */
package com.aipersimmon.ddd.messaging.kafka;
