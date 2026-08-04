package com.example.samples.s27.customer.application;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.operationlog.annotation.OperationLog;
import jakarta.validation.constraints.NotBlank;

/**
 * The domain deletion: close the account, with a reason.
 *
 * <p>The reason is recorded in the audit row and in the aggregate, and that is the difference between this and
 * a {@code deleted} flag in one sentence — six months later somebody can be told why, by reading either.
 */
@OperationLog(
    code = "customer.close",
    targetType = "Customer",
    targetId = "${input.customerId}",
    success = "Closed ${input.customerId}: ${defaultValue(input.reason, 'no reason given')}")
public record CloseCustomer(@NotBlank String customerId, String reason) implements Command<Void> {}
