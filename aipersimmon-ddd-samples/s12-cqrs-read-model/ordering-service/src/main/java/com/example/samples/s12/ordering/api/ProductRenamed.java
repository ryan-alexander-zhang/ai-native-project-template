package com.example.samples.s12.ordering.api;

import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.Externalized;
import com.aipersimmon.ddd.integration.IntegrationEvent;

/**
 * This context's own model of the catalogue's contract. A different Java class from the publisher's, with no
 * shared jar — they agree on the logical pair {@code (name, version)} from {@code @EventType}, never on a
 * class name. S4 argues that at length; it is unchanged here.
 *
 * <p>{@code @Externalized} on a consumer's copy is the <strong>subscription</strong>: the consumer bridge
 * derives its topic set from the externalized events it can see. So the topic must match the publisher's, and
 * the placeholder is how a deployment keeps them aligned.
 */
@EventType(name = "com.example.samples.catalog.ProductRenamed", version = 1, source = "/catalog")
@Externalized("${ordering.catalog-events-topic:catalog.events}")
public record ProductRenamed(String sku, String name) implements IntegrationEvent {

  @Override
  public String subject() {
    return sku;
  }
}
