package com.example.samples.s10.points.application;

import com.aipersimmon.ddd.cqrs.Command;
import com.example.samples.s10.points.domain.ReserveOutcome;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/** TCC Try. */
public record ReservePoints(
    @NotBlank String reference, @NotBlank String accountId, @Positive int points)
    implements Command<ReserveOutcome> {}
