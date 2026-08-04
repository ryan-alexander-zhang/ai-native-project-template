package com.example.samples.s28.reconciliation.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Ask for an export. Returns whether this call is the one that created it.
 *
 * <p>The boolean is what the controller turns into "202, and here is the job" either way — the distinction it
 * carries is only for the client's benefit, not for its control flow. Which is the whole shape of an
 * idempotent submission: the second attempt is not an error, it is not a second job, and the caller does not
 * have to know which attempt it was.
 *
 * @param exportId the client's id for this request; see {@code ExportJobId} for why the client supplies it
 * @param period the settlement period, {@code yyyy-MM}
 */
public record SubmitExport(
    @NotBlank String exportId, @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}") String period)
    implements Command<Boolean> {}
