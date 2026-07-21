package com.aipersimmon.ddd.messaging.kafka;

import com.aipersimmon.ddd.outbox.OutboxDispatcher;
import com.aipersimmon.ddd.outbox.OutboxMessage;
import java.util.Optional;

/**
 * The single {@link OutboxDispatcher} the relay injects when the Kafka transport is present: it
 * holds both delivery legs and routes each outbox message by its event's <em>reach</em>.
 *
 * <ul>
 *   <li><strong>LOCAL</strong> (the default — the event has no {@code @Externalized}): the
 *       in-process leg republishes it through Spring's {@code ApplicationEventPublisher}, so it
 *       never touches the broker.
 *   <li><strong>EXTERNAL</strong> (the event is {@code @Externalized} to a topic): the Kafka leg
 *       sends it to that topic. It is <em>not</em> also republished in process here — its local
 *       delivery comes back through the consumer bridge instead. That is the core
 *       no-double-delivery invariant: every event reaches local {@code @EventListener}s by exactly
 *       one path (in-process for LOCAL, bridge for EXTERNAL), so the inbox only has to guard the
 *       bridge.
 * </ul>
 *
 * <p>Reach is looked up from the resolved {@link ExternalizedRoutes} by the message's {@code (type,
 * version)} — the same pair the publisher stamped — so routing never reconstructs the event just to
 * read an annotation on the hot path. This dispatcher is the built-in "compose the legs / route by
 * type" {@code OutboxDispatcher} the outbox auto-configuration documents as the extension seam; it
 * stays a single outbox row and one dispatch per message, so atomicity and at-least-once are
 * unchanged.
 */
public class RoutingOutboxDispatcher implements OutboxDispatcher {

  private final OutboxDispatcher localLeg;
  private final KafkaOutboxDispatcher externalLeg;
  private final ExternalizedRoutes routes;

  public RoutingOutboxDispatcher(
      OutboxDispatcher localLeg, KafkaOutboxDispatcher externalLeg, ExternalizedRoutes routes) {
    this.localLeg = localLeg;
    this.externalLeg = externalLeg;
    this.routes = routes;
  }

  @Override
  public void dispatch(OutboxMessage message) {
    Optional<String> topic = routes.topicFor(message.type(), message.version());
    if (topic.isPresent()) {
      externalLeg.dispatch(message, topic.get());
    } else {
      localLeg.dispatch(message);
    }
  }
}
