package com.example.samples.s28.reconciliation.application;

import com.aipersimmon.ddd.cqrs.Query;
import jakarta.validation.constraints.NotBlank;

/** Ask what still has to be sent. */
public record ImportBatchQuery(@NotBlank String batchId) implements Query<ImportBatchView> {}
