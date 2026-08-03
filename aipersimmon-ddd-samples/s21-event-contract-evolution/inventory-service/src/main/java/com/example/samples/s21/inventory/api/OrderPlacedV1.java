package com.example.samples.s21.inventory.api;

import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.Externalized;
import com.aipersimmon.ddd.integration.IntegrationEvent;

/**
 * Revision 1 of the ordering context's contract: one order, one line.
 *
 * <p><strong>Why a retired revision is kept in the consumer at all.</strong> Resolution is the exact
 * {@code (name, version)} pair and the library deliberately never falls back across versions
 * ({@code RegistryIntegrationEventCatalog}), so a revision this service has no class for is
 * dead-lettered. That is the right default — a payload read at the wrong revision is a silent
 * misinterpretation, which is worse than a loud rejection — and it makes the retired class the price
 * of being able to read what is already on the wire.
 *
 * <p><strong>What "still on the wire" covers</strong>, and it is longer than the topic's retention:
 * the publisher's outbox holds rows serialized at this revision (the publishing side has a test), a
 * dead-letter replay can put one back years later, and a consumer group reset re-reads whatever the
 * broker still has. Deleting this class is safe only after the longest of those has passed.
 *
 * <p><strong>The {@code @Externalized} here is a subscription</strong>, and it names a
 * <em>different</em> topic from the newer revisions on purpose: this is what a topic move looks like
 * from the consuming side. The subscription set is the union of the topics the declared revisions
 * name, so during a move a consumer reads both — and dropping the annotation from this class stops it
 * reading the old topic <em>silently</em>, since a topic nobody subscribed to raises nothing.
 *
 * <p>No handler is typed for this class. It exists to be deserialized and immediately upcast; see
 * {@code OrderContractUpcasters}.
 */
@EventType(name = "com.example.samples.ordering.OrderPlaced", version = 1, source = "/ordering")
@Externalized("${inventory.legacy-events-topic:ordering.events.v1}")
public record OrderPlacedV1(String orderId, String sku, int quantity) implements IntegrationEvent {

  @Override
  public String subject() {
    return orderId;
  }
}
