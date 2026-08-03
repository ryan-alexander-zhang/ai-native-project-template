package com.example.ordering.api;

import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.Externalized;
import com.aipersimmon.ddd.integration.IntegrationEvent;

/**
 * Integration event asking the inventory context to release a reservation — the ordering context's
 * cross-context contract for the stock-release compensation. The process manager emits it (carrying
 * the {@code reservationId} inventory handed back on reservation) when a payment decline forces it
 * to undo the held stock before the order can be cancelled.
 *
 * <h2>A request, not a fact</h2>
 *
 * <p>Most of this topic's traffic announces things that already happened; this event <em>asks
 * another context to act</em>. It rides the same machinery on purpose — the outbox, the topic, the
 * ordering guarantee — but the consumption rule differs, and that is what this marking exists for:
 * a fact consumed twice is merely recorded twice, a request consumed twice does the work twice.
 * <strong>Any consumer acting on this event must deduplicate by {@code reservationId}</strong>, as
 * the inventory context does: {@code Reservation.markReleased()} flips exactly once, so a
 * redelivered release hands no stock back a second time. Transport dedupe (the inbox) is a window,
 * not a guarantee — see the retention reasoning in the consuming application's configuration.
 */
@EventType(name = "com.example.ordering.StockReleaseRequested", version = 1, source = "/ordering")
@Externalized("ordering.events")
public record StockReleaseRequested(String orderId, String reservationId)
    implements IntegrationEvent {

  public StockReleaseRequested {
    // Both ids are the whole message: without them there is nothing to release.
    Contract.required(orderId, "orderId");
    Contract.required(reservationId, "reservationId");
  }

  @Override
  public String subject() {
    return orderId();
  }
}
