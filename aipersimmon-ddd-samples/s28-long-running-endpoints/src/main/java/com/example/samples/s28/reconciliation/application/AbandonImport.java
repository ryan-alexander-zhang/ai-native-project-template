package com.example.samples.s28.reconciliation.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.constraints.NotBlank;

/**
 * Give up on an upload, with a reason.
 *
 * <p>The counterpart every resumable protocol needs and most forget: without it, an abandoned batch is
 * indistinguishable from one whose client is about to come back, so nothing can ever clean up and the id stays
 * taken forever.
 */
public record AbandonImport(@NotBlank String batchId, String reason) implements Command<Boolean> {}
