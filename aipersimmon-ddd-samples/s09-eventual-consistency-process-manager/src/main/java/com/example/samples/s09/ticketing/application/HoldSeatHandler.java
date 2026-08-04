package com.example.samples.s09.ticketing.application;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s09.ticketing.domain.HoldOutcome;
import com.example.samples.s09.ticketing.domain.SeatClass;
import com.example.samples.s09.ticketing.domain.SeatClassId;
import com.example.samples.s09.ticketing.domain.SeatClasses;
import com.example.samples.s09.ticketing.domain.TicketingErrorCode;
import com.aipersimmon.ddd.application.EntityNotFoundException;
import java.time.Clock;
import org.springframework.stereotype.Component;

/**
 * A participant step: take the seat, then tell the coordinator what happened.
 *
 * <p>The two branches are the shape every participant in this flow has. {@code SOLD_OUT} is reported as
 * a fact, not thrown — a business refusal has to reach the flow so it can compensate, and an exception
 * would only reach the effect relay, which would retry a request that will be refused identically
 * forever. {@code ALREADY_HELD} is reported as success, because that is what a redelivered effect looks
 * like from here.
 *
 * <p>The notification is in the same transaction as the seat change: either the seat is held and the flow
 * knows, or neither. An after-commit notification would open a window in which the seat is gone and the
 * flow is still waiting — recoverable only by the step's deadline, which is a much worse way to learn.
 */
@Component
class HoldSeatHandler implements CommandHandler<HoldSeat, Void> {

  private final SeatClasses seatClasses;
  private final TicketingProcess process;
  private final Clock clock;

  HoldSeatHandler(SeatClasses seatClasses, TicketingProcess process, Clock clock) {
    this.seatClasses = seatClasses;
    this.process = process;
    this.clock = clock;
  }

  @Override
  public Void handle(HoldSeat command, CommandContext context) {
    SeatClassId id = new SeatClassId(command.seatClass());
    SeatClass seatClass =
        seatClasses
            .find(id)
            .orElseThrow(
                () ->
                    new EntityNotFoundException(
                        TicketingErrorCode.SEAT_CLASS_NOT_FOUND,
                        "no seat class '" + command.seatClass() + "'"));

    HoldOutcome outcome = seatClass.hold(command.orderId(), clock.instant());
    seatClasses.save(seatClass);

    if (outcome == HoldOutcome.SOLD_OUT) {
      process.seatSoldOut(command.orderId(), "no " + command.seatClass() + " seats left", context);
    } else {
      process.seatHeld(command.orderId(), context);
    }
    return null;
  }
}
