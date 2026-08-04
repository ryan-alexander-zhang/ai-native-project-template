package com.example.samples.s27.customer.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.constraints.NotBlank;

/** Undo a closure. Exists because domain state can be undone, and that is half the reason to prefer it. */
public record ReopenCustomer(@NotBlank String customerId) implements Command<Void> {}
