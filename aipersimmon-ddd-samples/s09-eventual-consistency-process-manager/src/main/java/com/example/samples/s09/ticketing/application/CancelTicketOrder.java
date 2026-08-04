package com.example.samples.s09.ticketing.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.constraints.NotBlank;

/** Flow effect, compensating and terminal: the order did not happen, and this is why. */
public record CancelTicketOrder(@NotBlank String orderId, @NotBlank String reason)
    implements Command<Void> {}
