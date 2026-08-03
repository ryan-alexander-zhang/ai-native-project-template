package com.example.samples.s04.ordering.api;

import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.Externalized;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import java.util.List;

/**
 * The published contract: what this context tells the rest of the world when an order is placed.
 *
 * <p>It lives in {@code ..api..} because that is what an ArchUnit rule in the library enforces, and
 * the rule is enforcing a real distinction — everything else in this service is an implementation
 * detail that may change freely, and this may not. Carry ids and the minimum a consumer needs; a
 * published event that mirrors the aggregate turns every internal change into a broadcast.
 *
 * <p>{@code @EventType} is the identity on the wire: a logical name and a payload version, never the
 * Java class name. The consumer in this same sample declares its <em>own</em> class for this
 * contract, with fewer fields, and the two agree because they agree on {@code (name, version)}.
 *
 * <p>{@code @Externalized} is the second, separate decision: this fact is part of another process's
 * diet. Without the annotation the event stays in-process and never reaches a broker, however much
 * Kafka is on the classpath — externalization is opt-in per event, so adding a transport does not put
 * every internal signal on the wire. The topic is a {@code ${property}} placeholder because where it
 * goes is deployment, while whether it goes is contract.
 *
 * <p>{@link #subject()} is the ordering key. Returning the order id is what keeps one order's events
 * in order on the topic — Kafka orders within a partition, and the partition is chosen from this.
 * Return null and delivery falls back to the event id, which spreads one aggregate's events across
 * partitions and loses their order.
 */
@EventType(name = "com.example.samples.ordering.OrderPlaced", version = 1, source = "/ordering")
@Externalized("${ordering.events-topic:ordering.events}")
public record OrderPlaced(String orderId, String customerId, List<Line> lines)
    implements IntegrationEvent {

  /** Only what a consumer needs: what, and how many. No prices, no internal ids. */
  public record Line(String sku, int quantity) {}

  @Override
  public String subject() {
    return orderId;
  }
}
