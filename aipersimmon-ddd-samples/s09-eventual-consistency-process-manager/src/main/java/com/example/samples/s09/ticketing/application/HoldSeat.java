package com.example.samples.s09.ticketing.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.constraints.NotBlank;

/**
 * Flow effect: take a seat for this order.
 *
 * <p>It carries the seat class rather than looking it up, because the coordinator that staged it cannot
 * read the order — a {@code ProcessDefinition} does no I/O. Everything a step needs travels in the
 * effect, and everything a <em>later</em> step needs travels in the flow's state.
 */
public record HoldSeat(@NotBlank String orderId, @NotBlank String seatClass)
    implements Command<Void> {}
