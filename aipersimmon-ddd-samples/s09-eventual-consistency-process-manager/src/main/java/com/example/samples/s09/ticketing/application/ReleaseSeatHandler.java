package com.example.samples.s09.ticketing.application;

import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s09.ticketing.domain.SeatClass;
import com.example.samples.s09.ticketing.domain.SeatClassId;
import com.example.samples.s09.ticketing.domain.SeatClasses;
import com.example.samples.s09.ticketing.domain.TicketingErrorCode;
import java.time.Clock;
import org.springframework.stereotype.Component;

/**
 * The compensating participant step. It reports success whether or not there was anything to release:
 * a compensation for a step that never took effect is not a failure, and the flow needs to move on
 * either way.
 */
@Component
class ReleaseSeatHandler implements CommandHandler<ReleaseSeat, Void> {

  private final SeatClasses seatClasses;
  private final TicketingProcess process;
  private final Clock clock;

  ReleaseSeatHandler(SeatClasses seatClasses, TicketingProcess process, Clock clock) {
    this.seatClasses = seatClasses;
    this.process = process;
    this.clock = clock;
  }

  @Override
  public Void handle(ReleaseSeat command, CommandContext context) {
    SeatClassId id = new SeatClassId(command.seatClass());
    SeatClass seatClass =
        seatClasses
            .find(id)
            .orElseThrow(
                () ->
                    new EntityNotFoundException(
                        TicketingErrorCode.SEAT_CLASS_NOT_FOUND,
                        "no seat class '" + command.seatClass() + "'"));

    seatClass.release(command.orderId(), clock.instant());
    seatClasses.save(seatClass);
    process.seatReleased(command.orderId(), context);
    return null;
  }
}
