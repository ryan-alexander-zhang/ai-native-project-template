package com.example.samples.s27.customer.application;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.operationlog.annotation.OperationLog;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Change the address — audited, and the audit template is the interesting part.
 *
 * <p>The summary says <em>that</em> the address changed and masks the new one. The obvious template says
 * "changed from A to B", and that is the one line of code that puts a person's old email into an append-only
 * table with a multi-year retention policy and no update port. An erasure then has to reach into the audit
 * log, which is the one place it is least welcome. {@code ErasureAndAuditTest} asserts the absence.
 *
 * <p>{@code mask} is not deletion: it keeps the first and last character. That is enough for a support agent
 * confirming what is on file and not enough to be the address — see S14 §6 for exactly what it discloses.
 */
@OperationLog(
    code = "customer.email.change",
    targetType = "Customer",
    targetId = "${input.customerId}",
    success = "Changed the email of ${input.customerId} to ${mask(input.email)}",
    failure = "Could not change the email of ${input.customerId}: ${failure.code}")
public record ChangeEmail(@NotBlank String customerId, @NotBlank @Email String email)
    implements Command<Void> {}
