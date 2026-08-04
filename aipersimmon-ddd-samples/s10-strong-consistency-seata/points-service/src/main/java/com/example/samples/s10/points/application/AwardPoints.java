package com.example.samples.s10.points.application;

import com.aipersimmon.ddd.cqrs.Command;
import com.example.samples.s10.points.domain.AwardOutcome;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/** The AT participant's one command: award the points, in the caller's global transaction. */
public record AwardPoints(
    @NotBlank String reference, @NotBlank String accountId, @Positive int points)
    implements Command<AwardOutcome> {}
