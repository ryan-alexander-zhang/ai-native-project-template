package com.aipersimmon.ddd.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link OutboxDispatcher} that logs each message instead of sending it to
 * a broker, so the outbox works out of the box. Replace it with a broker-backed
 * dispatcher (for example from a messaging starter) by defining your own
 * {@code OutboxDispatcher} bean.
 */
public class LoggingOutboxDispatcher implements OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(LoggingOutboxDispatcher.class);

    @Override
    public void dispatch(OutboxMessage message) {
        log.info("outbox dispatch (logging only): type={} eventId={} correlationId={} causationId={} payload={}",
                message.type(), message.eventId(), message.correlationId(), message.causationId(),
                message.payload());
    }
}
