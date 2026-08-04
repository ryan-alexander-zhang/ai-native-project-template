package com.example.samples.s27.customer.application;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.operationlog.annotation.OperationLog;
import jakarta.validation.constraints.NotBlank;

/**
 * The infrastructure switch, as a command an operator can invoke.
 *
 * <p>It records no domain event and asks the aggregate nothing, because there is nothing to ask: hiding a row
 * is not something that happens to a customer in any sense the model recognises. What makes it a command at
 * all is that somebody has to be allowed to do it, and somebody has to be able to find out that they did.
 *
 * <p><strong>Which is why the audit annotation is not optional here, and is the condition on using a switch at
 * all.</strong> Domain state leaves two traces by itself — the state and its reason — so an unaudited closure
 * is still explicable from the data. A logical delete leaves <em>nothing</em>: the row vanishes from every
 * read, no event was published, no field says why or by whom. The audit row is the only record that the
 * operation ever happened, so "is this audited" is the question that decides whether an infrastructure switch
 * is an acceptable design or an untraceable one.
 */
@OperationLog(
    code = "customer.suppress",
    targetType = "Customer",
    targetId = "${input.customerId}",
    success = "Suppressed the row for ${input.customerId} (infrastructure, not a business state)",
    // A suppression of a row that is already hidden, or was never there, changes nothing. Recorded as a
    // rejection rather than a success so the trail does not suggest an effect there was not.
    rejectedWhen = "${resultProjection}")
public record SuppressCustomer(@NotBlank String customerId) implements Command<Boolean> {}
