package com.example.samples.s27.customer.application;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.operationlog.annotation.OperationLog;
import jakarta.validation.constraints.NotBlank;

/**
 * Un-hide the row.
 *
 * <p>Trivial to implement, and worth having for the comparison it makes possible: this is the one thing an
 * infrastructure switch does better than domain state. An operator can undo a mistake without the model
 * needing a concept for "was hidden and is not any more" — which is the honest reason teams reach for a
 * logical delete, and a good reason, as long as the answer to "who hid it" is a row somewhere.
 */
@OperationLog(
    code = "customer.restore",
    targetType = "Customer",
    targetId = "${input.customerId}",
    success = "Restored the row for ${input.customerId}",
    rejectedWhen = "${resultProjection}")
public record RestoreCustomer(@NotBlank String customerId) implements Command<Boolean> {}
