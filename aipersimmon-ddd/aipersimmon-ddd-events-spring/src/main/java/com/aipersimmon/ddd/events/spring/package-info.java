/**
 * Spring adapter for the domain-event publisher port:
 * {@link com.aipersimmon.ddd.events.spring.SpringDomainEvents} hands each event to
 * Spring's {@code ApplicationEventPublisher}, and
 * {@link com.aipersimmon.ddd.events.spring.AipersimmonDddEventsAutoConfiguration}
 * wires it automatically. Delivery is synchronous, same-thread, and
 * same-transaction, so handlers run inline within the caller's transaction.
 */
package com.aipersimmon.ddd.events.spring;
