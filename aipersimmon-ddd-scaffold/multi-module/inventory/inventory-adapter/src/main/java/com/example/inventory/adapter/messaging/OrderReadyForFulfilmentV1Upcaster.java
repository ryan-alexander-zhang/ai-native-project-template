package com.example.inventory.adapter.messaging;

import com.aipersimmon.ddd.integration.EventUpcaster;
import com.example.ordering.api.OrderReadyForFulfilment;
import com.example.ordering.api.OrderReadyForFulfilmentV1;
import org.springframework.stereotype.Component;

/**
 * Carries a retired {@link OrderReadyForFulfilmentV1} message forward to {@link
 * OrderReadyForFulfilment} (v2), so this consumer's listener only faces the current revision .
 * Registered once, here at the contract boundary — the consumer bridge applies it before dispatch,
 * which is what let {@code OrderReadyForFulfilmentListener} collapse its one-method-per-revision
 * pair into a single method. The framework reads the {@code (name, v1 → v2)} registration from the
 * two classes' own {@code @EventType} contracts and refuses at startup anything that does not line
 * up.
 *
 * <p><strong>The upcast supplies nothing, and that is the point worth keeping.</strong> v2 added
 * {@code reservationDeadline}. A v1 message does not carry one, and none can be recovered: the
 * value is ordering's configured timeout added to the moment it published, and the envelope's
 * {@code occurredAt} gives the moment but not the budget. So the upcast states no deadline — which
 * is precisely what v1 always meant. Inventory does not act on the field today; writing the upcast
 * as a deliberate "unknown" rather than a plausible-looking instant is what keeps that honest on
 * the day a consumer does act on it, because a fabricated deadline is indistinguishable from a real
 * one at the point of use.
 *
 * <p>The line shape is mapped element by element because the two revisions deliberately share no
 * Java type — sharing the nested record would couple the frozen revision to the live one (see
 * {@code OrderReadyForFulfilmentV1.Line}).
 *
 * <h2>When to delete this class</h2>
 *
 * <p>Together with {@link OrderReadyForFulfilmentV1}, and on the same clock: when the topic's
 * retention has passed the deployment that stopped publishing v1 and the inbox's redelivery window
 * has closed behind it. Until then, deleting it early turns the remaining v1 backlog into dead
 * letters — same asymmetry as the retired class itself.
 */
@Component
public class OrderReadyForFulfilmentV1Upcaster
    implements EventUpcaster<OrderReadyForFulfilmentV1, OrderReadyForFulfilment> {

  @Override
  public OrderReadyForFulfilment upcast(OrderReadyForFulfilmentV1 event) {
    return new OrderReadyForFulfilment(
        event.orderId(),
        event.lines().stream()
            .map(line -> new OrderReadyForFulfilment.Line(line.sku(), line.quantity()))
            .toList(),
        null);
  }
}
