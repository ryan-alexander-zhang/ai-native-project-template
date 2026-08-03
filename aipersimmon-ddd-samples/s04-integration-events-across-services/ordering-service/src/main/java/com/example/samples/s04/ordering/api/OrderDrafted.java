package com.example.samples.s04.ordering.api;

import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.IntegrationEvent;

/**
 * The contrast: an integration event with <strong>no</strong> {@code @Externalized}.
 *
 * <p>It is published through exactly the same port, by the same handler, and it never reaches the
 * broker — it is delivered in-process to local {@code @EventListener}s and that is all. This is the
 * answer to "which events are worth crossing a service boundary": a draft is this context's business,
 * and nobody outside needs to know a user is still typing.
 *
 * <p>The test asserts the difference at the wire: same code path, one record on the topic, not two.
 */
@EventType(name = "com.example.samples.ordering.OrderDrafted", version = 1, source = "/ordering")
public record OrderDrafted(String orderId, String customerId) implements IntegrationEvent {

  @Override
  public String subject() {
    return orderId;
  }
}
