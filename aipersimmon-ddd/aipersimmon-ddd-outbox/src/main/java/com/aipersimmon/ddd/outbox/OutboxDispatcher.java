package com.aipersimmon.ddd.outbox;

/**
 * Port the relay uses to deliver a stored outbox message to a broker. This core ships only a
 * logging default and an in-process republisher; a messaging starter supplies a real transport. A
 * dispatch that throws leaves the row unsent to be retried on the next poll.
 */
public interface OutboxDispatcher {

  void dispatch(OutboxMessage message);
}
