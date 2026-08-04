package com.example.samples.s12.catalog.api;

import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.Externalized;
import com.aipersimmon.ddd.integration.IntegrationEvent;

/**
 * The published contract: this product is now called that.
 *
 * <p><strong>It carries the new name, not just the sku.</strong> The alternative — publish "sku-keyboard
 * changed, come and ask" — is the design that turns every rename into a fan-out of synchronous calls back
 * into this service, at exactly the moment the rename is interesting to everybody. Carrying the value
 * makes the consumer's update local, which is the whole point of the copy it keeps.
 *
 * <p>The cost of carrying it is that the value can be stale on arrival: two renames in quick succession
 * can be consumed out of order unless something orders them. {@link #subject()} returns the sku, which is
 * the partition key, and Kafka orders within a partition — so two renames of <em>the same</em> product
 * arrive in order. Two renames of different products may not, and nothing needs them to.
 *
 * <p>{@code @Externalized} is the separate decision from {@code @EventType}: this fact is part of another
 * context's diet. Without it the event stays in this JVM however much Kafka is on the classpath.
 */
@EventType(name = "com.example.samples.catalog.ProductRenamed", version = 1, source = "/catalog")
@Externalized("${catalog.events-topic:catalog.events}")
public record ProductRenamed(String sku, String name) implements IntegrationEvent {

  @Override
  public String subject() {
    return sku;
  }
}
