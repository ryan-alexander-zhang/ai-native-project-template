package com.example.samples.s07.payments.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.constraints.NotBlank;

/**
 * Ask a human to look at this payment. Carries a reason and nothing else, because an escalation is an
 * admission that we do not know what to do — a command that also decided something would be an
 * escalation in name only.
 *
 * <p>Its own command rather than a flag on {@link RecordGatewayResult} for a reason worth stating: the
 * two are triggered by opposite events. A result is news; an escalation is the <em>absence</em> of news
 * past a deadline, and there is nothing to record a result from.
 */
public record EscalatePayment(@NotBlank String paymentId, @NotBlank String reason)
    implements Command<Void> {}
