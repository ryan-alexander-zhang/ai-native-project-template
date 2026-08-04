package com.example.samples.s10.banking.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/** Take money out of one account. One aggregate, one transaction — the ordinary case. */
public record DebitAccount(
    @NotBlank String accountId, @Positive long amountMinor, @NotBlank String reference)
    implements Command<Void> {}
