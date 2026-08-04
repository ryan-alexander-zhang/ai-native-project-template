package com.example.samples.s23.ordering.api;

import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.IntegrationEvent;

/**
 * The order was placed. Version 1, and it stayed version 1 across all four migrations — which is the point.
 *
 * <p>{@code shipTo} is one string here even though the table now holds two columns. That is not laziness:
 * a published contract is not a projection of a table, so a structural migration is not a contract change,
 * and a consumer that was reading this before V2 is unaffected by V2 and V3. Had the event been generated
 * from the row — a "just serialise the entity" shortcut — the split would have silently become a breaking
 * change published to everyone, with no version bump and no upcaster, and the first symptom would have been
 * in someone else's service.
 *
 * <p>The reverse also holds and is the reason S21 exists separately: a contract change is not a migration.
 * The two schedules are independent, and coupling them is what makes people believe a deploy has to be
 * simultaneous.
 */
@EventType(name = "com.example.samples.ordering.OrderPlaced", version = 1, source = "/ordering")
public record OrderPlaced(String orderId, String customerId, String sku, int quantity, String shipTo)
    implements IntegrationEvent {

  @Override
  public String subject() {
    return orderId;
  }
}
