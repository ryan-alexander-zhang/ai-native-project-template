package com.aipersimmon.ddd.outbox.jdbc;

/**
 * Port the relay uses to deliver a stored outbox message to a broker. This module
 * ships only a logging default; a messaging starter supplies a real transport.
 * A dispatch that throws leaves the row unsent to be retried on the next poll.
 */
public interface OutboxDispatcher {

    void dispatch(OutboxMessage message);
}
