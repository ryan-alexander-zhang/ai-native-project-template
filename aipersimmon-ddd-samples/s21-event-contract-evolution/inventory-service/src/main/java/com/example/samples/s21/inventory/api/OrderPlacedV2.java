package com.example.samples.s21.inventory.api;

import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.Externalized;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import java.util.List;

/**
 * Revision 2: many lines where v1 had one.
 *
 * <p>This is the shape of change that <em>forces</em> a version bump. A new optional field does not —
 * an older consumer ignores an unknown JSON property and keeps working, which is the cheap path and
 * the one to prefer (a test pins it). But v1's {@code sku}/{@code quantity} and v2's {@code lines}
 * cannot both be the same field, so no amount of Jackson leniency bridges them: a v1 consumer reading
 * a v2 payload would find its two fields absent and quietly reserve nothing.
 *
 * <p>It is also losslessly derivable from v1 — one line becomes a list of one — which is what makes a
 * mechanical translation possible at all. "Needs a version" and "can be upcast" are separate
 * questions, and this revision happens to answer yes to both.
 */
@EventType(name = "com.example.samples.ordering.OrderPlaced", version = 2, source = "/ordering")
@Externalized("${inventory.ordering-events-topic:ordering.events}")
public record OrderPlacedV2(String orderId, List<OrderLine> lines) implements IntegrationEvent {

  @Override
  public String subject() {
    return orderId;
  }
}
