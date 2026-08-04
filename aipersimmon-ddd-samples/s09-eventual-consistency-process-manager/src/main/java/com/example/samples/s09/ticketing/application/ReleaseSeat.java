package com.example.samples.s09.ticketing.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.constraints.NotBlank;

/** Flow effect, compensating: give the seat back. */
public record ReleaseSeat(@NotBlank String orderId, @NotBlank String seatClass)
    implements Command<Void> {}
