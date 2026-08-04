package com.example.samples.s22.inventory.api;

import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.Externalized;
import com.aipersimmon.ddd.integration.IntegrationEvent;

/**
 * The contract as inventory needs it. No {@code customerId}: the publisher sends it, this context does
 * not need it, so this context does not model it.
 *
 * <p>{@code @Externalized} here is the <strong>subscription</strong>, not a publication route — the
 * consumer bridge derives its topic set from the externalized events it can see. The operational
 * consequence, and the reason it is worth repeating in this sample, is that the dead-letter topic the
 * error handler publishes to is {@code <this topic>.DLT}: a name derived from a property in this file,
 * which somebody has to have provisioned before the first bad record arrives.
 */
@EventType(name = "com.example.samples.ordering.OrderPlaced", version = 1, source = "/ordering")
@Externalized("${inventory.ordering-events-topic:s22.ordering.events}")
public record OrderPlaced(String orderId, String sku, int quantity) implements IntegrationEvent {

  @Override
  public String subject() {
    return orderId;
  }
}
