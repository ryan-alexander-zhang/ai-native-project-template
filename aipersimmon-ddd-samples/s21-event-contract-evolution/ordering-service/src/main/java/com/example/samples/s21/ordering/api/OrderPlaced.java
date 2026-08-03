package com.example.samples.s21.ordering.api;

import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.Externalized;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import java.util.List;

/**
 * The published contract, at revision 3 — and the only revision in this service's tree.
 *
 * <p>Its history is the subject of the sample:
 *
 * <ul>
 *   <li><strong>v1</strong> {@code (orderId, customerId, sku, quantity)} — one line per order, which
 *       stopped being true.
 *   <li><strong>v2</strong> {@code (orderId, customerId, lines[])} — a <em>restructuring</em>. Not
 *       expressible as an optional field, so the version had to move; and losslessly derivable from
 *       v1, so a consumer can be carried forward mechanically.
 *   <li><strong>v3</strong> {@code (…, warehouseCode)} — new information. Derivable from nothing: v1
 *       and v2 never carried it, so no consumer-side translation can supply it and the field has to
 *       be one whose <em>absence</em> means something.
 * </ul>
 *
 * <p><strong>A publisher holds one revision; a consumer holds every revision that can still
 * arrive.</strong> That asymmetry is why there is no shared contract jar: the two trees are not the
 * same shape, and they stop being the same shape the moment a deploy is in progress. v1 and v2 are
 * gone from here — this service cannot produce them any more — while the inventory service still
 * declares all three, because records at those revisions are still in flight.
 *
 * <p>"In flight" is longer than it sounds, and a test in this module pins the part people forget: the
 * outbox holds rows serialized <em>before</em> this deploy, each with the revision it was written at.
 * Deleting a revision here does not stop it being published; only draining does.
 *
 * <p>The version lives in {@code @EventType}, not in the class name and not in the topic. The topic
 * is unchanged across all three revisions, which is the default worth defaulting to: a topic per
 * revision multiplies the subscriptions every consumer must hold, and buys nothing the {@code (name,
 * version)} pair does not already give. (Where it does buy something is §6 of the companion
 * document, and the consumer side of this sample keeps one legacy topic to show the mechanics.)
 */
@EventType(name = "com.example.samples.ordering.OrderPlaced", version = 3, source = "/ordering")
@Externalized("${ordering.events-topic:ordering.events}")
public record OrderPlaced(
    String orderId, String customerId, List<Line> lines, String warehouseCode)
    implements IntegrationEvent {

  /** What v2 introduced: many of these where v1 had one sku and one quantity. */
  public record Line(String sku, int quantity) {}

  @Override
  public String subject() {
    return orderId;
  }
}
