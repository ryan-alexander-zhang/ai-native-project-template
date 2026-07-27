package com.aipersimmon.ddd.outbox;

import java.time.Instant;

/**
 * One dead letter as an operator needs to see it: which event, how hard the relay tried, why it
 * gave up, and what the last failure said.
 *
 * <p>Deliberately not the {@link OutboxMessage}. Triage asks "why did this not go out, and is it
 * worth replaying" — the payload answers none of that, and a listing that carries every message
 * body is both expensive and a way to spill event contents into an operations screen. {@link
 * DeadLetterStore#replay} needs only {@link #eventId}, which is what makes this the missing half of
 * that method.
 *
 * @param eventId the event's id — the handle {@link DeadLetterStore#replay} takes
 * @param type the event type (as published, for example {@code ordering.OrderPlaced})
 * @param version the event type's version
 * @param subject the aggregate the event is about, or null if the event named none
 * @param tenantId the tenant the event was produced under
 * @param occurredAt when the event happened (not when delivery was abandoned)
 * @param attempts how many delivery attempts were made, including the last failure
 * @param reason why the relay gave up
 * @param lastError a short description of the final failure, or null if none was recorded
 * @param failedAt when the relay gave up and moved the row here
 */
public record DeadLetter(
    String eventId,
    String type,
    int version,
    String subject,
    String tenantId,
    Instant occurredAt,
    int attempts,
    DeadLetterStore.Reason reason,
    String lastError,
    Instant failedAt) {}
