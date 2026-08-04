package com.example.samples.s22.inventory.adapter;

import com.aipersimmon.ddd.application.InboundEvents;
import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.integration.EventEnvelope;
import com.example.samples.s22.inventory.api.OrderPlaced;
import com.example.samples.s22.inventory.application.ReserveStock;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * An event arrives, a command goes in. It holds no rule and makes no decision.
 *
 * <p>Note that it cannot decline. There is no branch here for "this record looks wrong" and there should
 * not be, because the two things an adapter could do about a bad record — swallow it or log it — are the
 * two worst options available. Swallowing loses the fact with no trace; logging leaves it lost with a
 * trace nobody reads. Letting the exception out is what hands the decision to the error handler, which is
 * the only component that knows how many times this record has already been tried and where to put it
 * when the answer is "enough".
 */
@Component
class OrderPlacedListener {

  private final CommandBus commandBus;

  OrderPlacedListener(CommandBus commandBus) {
    this.commandBus = commandBus;
  }

  @EventListener
  void on(EventEnvelope<OrderPlaced> envelope) {
    OrderPlaced event = envelope.payload();
    commandBus.send(
        new ReserveStock(event.orderId(), event.sku(), event.quantity()),
        InboundEvents.commandContext(envelope));
  }
}
