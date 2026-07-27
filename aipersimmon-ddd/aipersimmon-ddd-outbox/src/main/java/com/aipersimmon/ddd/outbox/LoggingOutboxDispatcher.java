package com.aipersimmon.ddd.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link OutboxDispatcher} that logs each message instead of delivering it anywhere, for
 * smoke-testing the store-and-forward path without a transport. It is <strong>opt-in</strong>
 * ({@code aipersimmon.ddd.outbox.dispatch=logging}) and not a default: because it returns normally,
 * the relay marks every row sent, so choosing it discards integration events. Use a messaging
 * starter, or the in-process republisher, to actually deliver them.
 */
public class LoggingOutboxDispatcher implements OutboxDispatcher {

  private static final Logger log = LoggerFactory.getLogger(LoggingOutboxDispatcher.class);

  @Override
  public void dispatch(OutboxMessage message) {
    log.info(
        "outbox dispatch (logging only): type={} eventId={} correlationId={} causationId={} payload={}",
        message.type(),
        message.eventId(),
        message.correlationId(),
        message.causationId(),
        message.payload());
  }

  /** Logging delivers nowhere at all, let alone to an external target. */
  @Override
  public boolean reachesExternalTargets() {
    return false;
  }
}
