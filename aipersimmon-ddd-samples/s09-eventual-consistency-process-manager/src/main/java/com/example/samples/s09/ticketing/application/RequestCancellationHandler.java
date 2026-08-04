package com.example.samples.s09.ticketing.application;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import org.springframework.stereotype.Component;

/**
 * A cancellation request from outside, handed straight to the coordinator.
 *
 * <p>It deliberately does <strong>not</strong> cancel the order. Whether cancelling is even possible
 * depends on how far the flow has got — there may be a seat to release and money to give back, in a
 * particular order — and that knowledge lives in the flow, not here. A handler that cancelled the
 * aggregate directly would leave the flow still holding a seat and still expecting a ticket.
 */
@Component
class RequestCancellationHandler implements CommandHandler<RequestCancellation, Void> {

  private final TicketingProcess process;

  RequestCancellationHandler(TicketingProcess process) {
    this.process = process;
  }

  @Override
  public Void handle(RequestCancellation command, CommandContext context) {
    process.cancellationRequested(command.orderId(), command.reason(), context);
    return null;
  }
}
