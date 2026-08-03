package com.example.samples.s21.inventory.adapter;

import com.aipersimmon.ddd.application.InboundEvents;
import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.integration.EventEnvelope;
import com.example.samples.s21.inventory.api.OrderPlaced;
import com.example.samples.s21.inventory.application.ReserveStock;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * One listener, for the current revision only — which is the point of the upcaster chain above it.
 *
 * <p><strong>Where the absence is interpreted.</strong> A record that came in at v1 or v2 arrives here
 * with {@code warehouseCode} null, because no upcaster is allowed to invent one. Deciding what that
 * means is this consumer's business, and it is decided here, in one line, where it can be read and
 * changed — rather than inside an upcaster, where it would have become indistinguishable from data.
 * The rule is written down as a constant with a name, not as a bare string in an expression, because
 * "records from before warehouses existed belong to the main warehouse" is a business decision that
 * someone will want to find.
 *
 * <p>Note what the listener does <em>not</em> get to know: which revision the record was written at.
 * After normalisation {@code envelope.version()} describes the payload it is holding — v3 — and the
 * wire's original revision stays on the Kafka record's header where the application cannot see it
 * ({@code KafkaIntegrationEventListener:220-224}). That is deliberate and it is the right trade: a
 * consumer that branches on the wire revision has un-normalised itself, and re-acquired the per-revision
 * code the chain was built to delete. If a distinction genuinely matters, it has to be carried by the
 * payload — which is exactly what leaving the field absent does.
 */
@Component
class OrderPlacedListener {

  /**
   * What this service takes "no warehouse named" to mean. Records at v1 and v2 predate the field, so
   * this is the rule that lets them be processed at all.
   */
  private static final String WAREHOUSE_WHEN_UNSPECIFIED = "MAIN";

  private final CommandBus commandBus;

  OrderPlacedListener(CommandBus commandBus) {
    this.commandBus = commandBus;
  }

  @EventListener
  void on(EventEnvelope<OrderPlaced> envelope) {
    OrderPlaced event = envelope.payload();
    String warehouse =
        event.warehouseCode() == null ? WAREHOUSE_WHEN_UNSPECIFIED : event.warehouseCode();
    commandBus.send(
        new ReserveStock(
            event.orderId(),
            warehouse,
            event.lines().stream()
                .map(line -> new ReserveStock.Line(line.sku(), line.quantity()))
                .toList()),
        InboundEvents.commandContext(envelope));
  }
}
