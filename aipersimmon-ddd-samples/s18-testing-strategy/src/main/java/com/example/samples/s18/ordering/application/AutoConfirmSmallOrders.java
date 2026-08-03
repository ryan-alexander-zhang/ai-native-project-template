package com.example.samples.s18.ordering.application;

import com.aipersimmon.ddd.application.DomainEventHandler;
import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.CommandContexts;
import com.example.samples.s18.ordering.domain.OrderPlacedInContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * A subscriber that reacts by sending another command — the shape {@code RecordingCommandBus} exists
 * for. A unit test asserts <em>which</em> command was dispatched and that it inherited the causal
 * chain, without a bus, a database or a context.
 */
@Component
@DomainEventHandler
public class AutoConfirmSmallOrders {

  private static final long THRESHOLD_CENTS = 1000;

  private final CommandBus commandBus;

  public AutoConfirmSmallOrders(CommandBus commandBus) {
    this.commandBus = commandBus;
  }

  @EventListener
  public void on(OrderPlacedInContext event) {
    if (event.amountCents() > THRESHOLD_CENTS) {
      return;
    }
    // send(command, cause) keeps the correlation and records the cause, so the follow-up is traceable
    // back to the command that produced the event.
    CommandContexts.current()
        .ifPresentOrElse(
            cause -> commandBus.send(new ConfirmOrder(event.orderId().value()), cause),
            () -> commandBus.send(new ConfirmOrder(event.orderId().value())));
  }
}
