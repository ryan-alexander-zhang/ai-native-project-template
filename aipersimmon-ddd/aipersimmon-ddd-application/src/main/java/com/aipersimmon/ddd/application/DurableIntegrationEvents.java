package com.aipersimmon.ddd.application;

/**
 * Capability marker for an {@link IntegrationEvents} transport that persists each event durably
 * <em>before</em> delivery — a transactional outbox written in the same transaction as the
 * aggregate, drained by a relay. Such a transport survives a process crash and can forward
 * {@code @Externalized} events to an external broker; the in-process synchronous publisher cannot
 * and therefore does <strong>not</strong> implement this.
 *
 * <p>It carries no methods: it exists so a cross-process transport (e.g. the Kafka messaging
 * starter over {@code @Externalized} events) can assert at startup that the active publisher is
 * durable, turning a silent downgrade to in-process delivery into a boot-time failure rather than a
 * defect discovered only by inspecting the database.
 */
public interface DurableIntegrationEvents extends IntegrationEvents {}
