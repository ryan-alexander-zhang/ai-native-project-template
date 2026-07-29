package com.example.ordering.api;

import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.Externalized;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import java.util.List;

/**
 * Revision 1 of {@link OrderReadyForFulfilment}, kept so consumers can still read messages
 * published before {@code reservationDeadline} existed. <strong>Read-only: nothing publishes
 * this.</strong>
 *
 * <p>Same logical event name as revision 2, different {@code version} — which is the whole
 * mechanism. The catalog is keyed by {@code (name, version)}, so both classes register and an
 * arriving message is deserialised into whichever one its {@code ce_dataschema} version names.
 *
 * <p>The Java class needs a distinct name because two classes cannot share one; the {@code V1}
 * suffix is therefore a compile-time detail and appears nowhere on the wire. Note the direction of
 * the naming: the <em>unsuffixed</em> class is always the current revision, so ordinary producing
 * code never mentions a version, and a suffix is a signal that you are looking at something
 * retired. The alternative — freezing the plain name at v1 and calling the new one {@code V2} —
 * makes every future producer name a version and gets worse with each revision.
 *
 * <h2>When to delete this class</h2>
 *
 * <p>When no v1 message can still arrive: the topic's retention has passed the deployment that
 * stopped publishing v1, <em>and</em> the inbox's redelivery window has closed behind it. Both are
 * elapsed-time questions, so this is a scheduled cleanup rather than part of the migration.
 * Deleting it early turns a valid backlog into dead letters; keeping it forever costs one
 * unreferenced class and a line in the catalog. The asymmetry is why the safe default is to leave
 * it.
 *
 * <p>It is deliberately still {@code @Externalized} to the same topic. The annotation declares
 * where this type belongs, not that anything sends it, and keeping the declaration means the topic
 * list and the catalog are built from one consistent rule instead of one with an exception in it.
 */
@EventType(name = "com.example.ordering.OrderReadyForFulfilment", version = 1)
@Externalized("ordering.events")
public record OrderReadyForFulfilmentV1(String orderId, List<Line> lines)
    implements IntegrationEvent {

  public OrderReadyForFulfilmentV1 {
    lines = lines == null ? null : List.copyOf(lines);
  }

  @Override
  public String subject() {
    return orderId();
  }

  /**
   * Structurally identical to {@link OrderReadyForFulfilment.Line} and deliberately a separate
   * type. Sharing the nested record would couple the frozen revision to the live one, so a later
   * change to v2's line shape would silently rewrite what v1 claims to have meant — and the stored
   * messages this class exists to read would no longer match it.
   */
  public record Line(String sku, int quantity) {}
}
