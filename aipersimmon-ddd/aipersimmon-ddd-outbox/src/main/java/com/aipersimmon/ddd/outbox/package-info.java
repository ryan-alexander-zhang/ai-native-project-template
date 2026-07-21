/**
 * Storage-agnostic transactional-outbox core: the delivery contract ({@link
 * com.aipersimmon.ddd.outbox.OutboxDispatcher}) and the stored-message shape ({@link
 * com.aipersimmon.ddd.outbox.OutboxMessage}) shared by every storage backend and transport, plus
 * the two default dispatchers ({@link com.aipersimmon.ddd.outbox.LoggingOutboxDispatcher}, {@link
 * com.aipersimmon.ddd.outbox.InProcessOutboxDispatcher}) and the auto-configuration that selects
 * between them. Persistence (the writer and relay) lives in a storage starter that depends on this
 * core.
 */
package com.aipersimmon.ddd.outbox;
