package com.aipersimmon.ddd.application;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.integration.IntegrationEvent;

/**
 * Port for publishing an integration event to other bounded contexts. A command
 * handler calls this with a plain integration event and the {@link CommandContext}
 * of the command emitting it; the infrastructure layer decides the transport,
 * mints the event id and timestamp, and stamps the causal metadata onto the
 * outbound message — so the application stays free of clock, id generation, and
 * framework concerns.
 *
 * <p>The emitted event inherits the command's {@code correlationId} and records the
 * command as its {@code causationId} (the command's {@code messageId}), so the
 * outbound event is traceable back to what caused it.
 */
public interface IntegrationEvents {

    /**
     * Publish {@code event}, correlated to and caused by the command described by
     * {@code context}.
     */
    void publish(IntegrationEvent event, CommandContext context);
}
