package com.example.samples.s21.inventory.api;

import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.Externalized;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import java.util.List;

/**
 * Revision 3, and the only revision any handler in this service is typed for.
 *
 * <p>The name has no suffix on purpose: the current revision is the one the application code talks
 * about, and the retired ones are the ones that need qualifying. Renaming this class when v4 arrives
 * costs nothing, because the class name is not the wire identity.
 *
 * <p>{@code warehouseCode} is <strong>nullable, and that is a contract term, not an oversight.</strong>
 * v1 and v2 never carried it, so every record from those revisions arrives here with it absent — and no
 * upcaster may fill it in, because a fabricated value is indistinguishable from one the publisher
 * actually sent. A successor revision that adds information must therefore be a revision whose
 * successor <em>tolerates the absence</em>: nullable, or with a default documented on the contract. If
 * the new field cannot be absent, the honest options are a new logical event or a hard cutover, not an
 * upcaster with a plausible guess in it.
 *
 * <p>What the absence means is then the consumer's decision, taken in the open — see
 * {@code OrderPlacedListener}.
 */
@EventType(name = "com.example.samples.ordering.OrderPlaced", version = 3, source = "/ordering")
@Externalized("${inventory.ordering-events-topic:ordering.events}")
public record OrderPlaced(String orderId, List<OrderLine> lines, String warehouseCode)
    implements IntegrationEvent {

  @Override
  public String subject() {
    return orderId;
  }
}
