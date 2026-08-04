package com.example.samples.s09.ticketing.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/** The only command a client sends. Everything after it is the coordinator's work. */
public record PlaceTicketOrder(
    @NotBlank String customerId, @NotBlank String seatClass, @Positive long amountMinor)
    implements Command<String> {}
