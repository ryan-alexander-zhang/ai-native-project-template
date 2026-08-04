package com.example.samples.s28.reconciliation.application;

import com.aipersimmon.ddd.cqrs.Query;
import jakarta.validation.constraints.NotBlank;

/**
 * Poll one job.
 *
 * <p>Not cached, and that is not an oversight — see S26 for the machinery. A progress reading is the one kind of
 * answer for which a stale value is worse than a slow one: a client polling every second to watch a number move
 * would be served the same number from a cache and conclude the job is stuck.
 */
public record ExportJobQuery(@NotBlank String exportId) implements Query<ExportJobView> {}
