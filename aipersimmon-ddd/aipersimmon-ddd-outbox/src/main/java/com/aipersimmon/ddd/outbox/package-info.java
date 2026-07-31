/**
 * Storage-agnostic transactional-outbox core: the delivery contract ({@link
 * com.aipersimmon.ddd.outbox.OutboxDispatcher}), the stored-message shape ({@link
 * com.aipersimmon.ddd.outbox.OutboxMessage}), the permanent-vs-transient split ({@link
 * com.aipersimmon.ddd.outbox.FailureClassifier}), the retry schedule ({@link
 * com.aipersimmon.ddd.outbox.RetryBackoff}), and where a give-up goes ({@link
 * com.aipersimmon.ddd.outbox.DeadLetterStore}) — shared by every storage backend and transport.
 *
 * <p>Zero Spring, deliberately: this is the contract module that {@code -outbox-jdbc}, {@code
 * -outbox-mybatis-plus} and {@code -messaging-kafka} all depend on, so it is the kind of module a
 * domain layer may name, and it must not drag a framework in. The container half — the
 * auto-configuration that selects a dispatcher, the bound properties, the in-process dispatcher and
 * the event-type scanner — lives in {@code aipersimmon-ddd-outbox-spring-boot-starter}; persistence
 * (the writer and relay) lives in a storage module.
 */
package com.aipersimmon.ddd.outbox;
