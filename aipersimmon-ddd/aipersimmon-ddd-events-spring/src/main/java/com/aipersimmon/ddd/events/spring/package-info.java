/**
 * Spring adapters for the event publisher ports — the in-process, synchronous transport. {@link
 * com.aipersimmon.ddd.events.spring.SpringDomainEvents} and {@link
 * com.aipersimmon.ddd.events.spring.SpringIntegrationEvents} hand each event to Spring's {@code
 * ApplicationEventPublisher}, and {@link
 * com.aipersimmon.ddd.events.spring.AipersimmonDddEventsAutoConfiguration} wires them
 * automatically. Delivery is synchronous, same-thread, and same-transaction, so handlers run inline
 * within the caller's transaction. The integration publisher is provided only when the outbox
 * starter is absent.
 */
package com.aipersimmon.ddd.events.spring;
