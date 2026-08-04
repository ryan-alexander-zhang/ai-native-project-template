package com.example.samples.s09.ticketing.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.constraints.NotBlank;

/**
 * A customer changing their mind, mid-flow.
 *
 * <p>Not an effect — this one comes from outside, which is what makes it interesting: it can land at any
 * step, including one where there is already money to give back, and including steps where the right
 * answer is to ignore it. It is the reason {@code ProcessDecision.ignored} exists.
 */
public record RequestCancellation(@NotBlank String orderId, @NotBlank String reason)
    implements Command<Void> {}
