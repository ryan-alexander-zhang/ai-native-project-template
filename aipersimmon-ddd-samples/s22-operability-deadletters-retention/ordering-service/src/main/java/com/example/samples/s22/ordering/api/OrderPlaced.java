package com.example.samples.s22.ordering.api;

import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.Externalized;
import com.aipersimmon.ddd.integration.IntegrationEvent;

/**
 * What this context tells the world when an order is placed. S4 explains the contract; S22 adds the
 * two operational facts about it.
 *
 * <p><strong>The topic is a property, and that is a failure mode as well as a convenience.</strong>
 * Whether this event goes out is contract; where it goes is deployment. So a topic that was never
 * provisioned — or renamed in one environment and not the other — is a configuration error whose
 * only symptom is rows in {@code aipersimmon_dead_letter}. That is the whole first half of this
 * sample, and it is reproduced with a real broker rather than a stubbed failure.
 *
 * <p><strong>{@link #subject()} is what makes a dead letter actionable.</strong> It is the ordering
 * key on the topic, and it is also the column an operator greps: a give-up that names no aggregate
 * can be counted but not acted on. Returning null costs both at once.
 */
@EventType(name = "com.example.samples.ordering.OrderPlaced", version = 1, source = "/ordering")
@Externalized("${ordering.events-topic:s22.ordering.events}")
public record OrderPlaced(String orderId, String customerId, String sku, int quantity)
    implements IntegrationEvent {

  @Override
  public String subject() {
    return orderId;
  }
}
