package com.example.samples.s09.ticketing.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.constraints.NotBlank;

/** Flow effect: the last forward step, and the point of no return. */
public record IssueTicket(@NotBlank String orderId) implements Command<Void> {}
