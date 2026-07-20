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
     * {@code context}. Mints a fresh event id for this new message.
     */
    void publish(IntegrationEvent event, CommandContext context);

    /**
     * Publish {@code event} under a durable message identity assigned upstream by a
     * durable store — a Process Manager effect relay — replaying it verbatim. Unlike
     * {@link #publish}, the infrastructure mints nothing: {@code context} carries the
     * already-persisted identity ({@code messageId} equal to the effect id) and causal
     * chain, and they are stamped onto the envelope as-is — the event id becomes
     * {@code context.messageId()}, not a fresh random id. So every at-least-once
     * redelivery of the same staged effect reaches downstream consumers under the same
     * event id, and their inbox dedupes it to one logical event. The command side's
     * counterpart is {@link com.aipersimmon.ddd.cqrs.CommandBus#sendAs}.
     *
     * <p>An implementation backed by a uniqueness-enforcing store (an outbox keyed by
     * event id) must additionally make the write idempotent, so a redelivery that
     * re-inserts the same event id collapses to the existing row rather than failing.
     *
     * <p><strong>Infrastructure-only.</strong> This is not a business publication entry
     * point: application code and command handlers publish new events with
     * {@link #publish(IntegrationEvent, CommandContext)}, which mints identity. The
     * default implementation rejects the call; a transport that backs a durable runtime
     * overrides it.
     *
     * @param event   the event to publish
     * @param context the already-minted, persisted context to use verbatim
     * @throws UnsupportedOperationException if this transport does not back a durable runtime
     */
    default void publishAs(IntegrationEvent event, CommandContext context) {
        throw new UnsupportedOperationException(
                "this IntegrationEvents transport does not support staged (publishAs) publication");
    }
}
