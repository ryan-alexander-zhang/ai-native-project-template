package com.aipersimmon.ddd.messaging.kafka;

import com.aipersimmon.ddd.outbox.InFlightDispatch;
import com.aipersimmon.ddd.outbox.OutboxDispatcher;
import com.aipersimmon.ddd.outbox.OutboxMessage;

/**
 * The single {@link OutboxDispatcher} the relay injects when the Kafka transport is present: it
 * holds both delivery legs and routes each outbox message by the destination on its row.
 *
 * <ul>
 *   <li><strong>No destination</strong> (the event had no {@code @Externalized} when it was
 *       published): the in-process leg republishes it through Spring's {@code
 *       ApplicationEventPublisher}, so it never touches the broker.
 *   <li><strong>A destination</strong>: the Kafka leg sends it to that topic. It is <em>not</em>
 *       also republished in process here — its local delivery comes back through the consumer
 *       bridge instead. That is the core no-double-delivery invariant: every event reaches local
 *       {@code @EventListener}s by exactly one path (in-process for local events, the bridge for
 *       externalized ones), so the inbox only has to guard the bridge.
 * </ul>
 *
 * <p>The destination is read from the row, not resolved here. It was resolved once, in the
 * transaction that wrote the event, from the {@link ExternalizedRoutes} in force then. Resolving it
 * again at dispatch time made routing a property of whatever code happens to be deployed when the
 * relay gets to the row: an event published while it was externalized, whose annotation a later
 * version dropped, found no route, fell through to the in-process leg, and was marked sent — never
 * reaching the broker, with no exception, no dead letter and no consumer lag to notice. Reading the
 * column means the row still goes where it was addressed.
 *
 * <p>This is the built-in "compose the legs / route by row" {@code OutboxDispatcher} the outbox
 * auto-configuration documents as the extension seam; it stays a single outbox row and one dispatch
 * per message, so atomicity and at-least-once are unchanged.
 */
public class RoutingOutboxDispatcher implements OutboxDispatcher {

  private final OutboxDispatcher localLeg;
  private final KafkaOutboxDispatcher externalLeg;

  public RoutingOutboxDispatcher(OutboxDispatcher localLeg, KafkaOutboxDispatcher externalLeg) {
    this.localLeg = localLeg;
    this.externalLeg = externalLeg;
  }

  @Override
  public void dispatch(OutboxMessage message) {
    beginDispatch(message).awaitDelivery();
  }

  /**
   * Hands the message to whichever leg the row's destination names, without waiting for it. The
   * in-process leg has nothing to overlap — republishing is synchronous and already done when it
   * returns — so only the Kafka leg returns a real pending acknowledgement.
   */
  @Override
  public InFlightDispatch beginDispatch(OutboxMessage message) {
    if (message.destination() == null) {
      localLeg.dispatch(message);
      return InFlightDispatch.CONFIRMED;
    }
    return externalLeg.beginDispatch(message, message.destination());
  }
}
