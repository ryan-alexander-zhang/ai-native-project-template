package com.example.samples.s28.reconciliation.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.constraints.NotBlank;

/**
 * Close the upload.
 *
 * <p>Refused, naming the gaps, until every declared chunk is on record — which is why the client declared how
 * many there were. A completion that trusted the client's assertion of completeness would make the chunk numbers
 * decoration.
 *
 * @return false if it was already completed, so a client resuming into a completion it already got is fine
 */
public record CompleteImport(@NotBlank String batchId) implements Command<Boolean> {}
