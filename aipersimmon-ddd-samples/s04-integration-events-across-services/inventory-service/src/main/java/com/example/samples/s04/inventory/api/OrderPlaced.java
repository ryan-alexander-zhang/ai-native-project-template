package com.example.samples.s04.inventory.api;

import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.Externalized;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import java.util.List;

/**
 * This service's own model of the ordering context's contract.
 *
 * <p><strong>It is a different Java class from the publisher's, deliberately.</strong> There is no
 * shared jar: the two services are separate deployables, and a shared contract class would give them a
 * compile-time coupling — bump it and both must be released together, which is what the broker was
 * introduced to avoid. They agree because the wire identity is the logical pair {@code (name,
 * version)} from {@code @EventType}, never the Java class name.
 *
 * <p>Notice what is missing: {@code customerId}. The publisher sends it; inventory does not need it, so
 * inventory does not model it. Jackson ignores unknown properties, so the consumer's view of a contract
 * can legitimately be a subset — which is also why <em>adding</em> a field is a backward-compatible
 * change and removing one is not. (Evolution proper is S21.)
 *
 * <p>{@code @Externalized} here is the <strong>subscription</strong>, not a publication route: the
 * consumer bridge derives its topic set from the externalized events it can see, and
 * {@code OnExternalizedEventsCondition} does not even register the bridge when there are none — "with
 * zero externalized events there is no topic to subscribe to". So the topic name must match the
 * publisher's, and the property placeholder is how a deployment keeps them aligned.
 */
@EventType(name = "com.example.samples.ordering.OrderPlaced", version = 1, source = "/ordering")
@Externalized("${inventory.ordering-events-topic:ordering.events}")
public record OrderPlaced(String orderId, List<Line> lines) implements IntegrationEvent {

  public record Line(String sku, int quantity) {}

  @Override
  public String subject() {
    return orderId;
  }
}
