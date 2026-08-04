package com.example.samples.s28.reconciliation.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.constraints.NotBlank;

/**
 * Ask an export to stop. Returns whether anything changed.
 *
 * <p>Worth knowing what happens when this races the claim, because it is the ordinary case rather than an exotic
 * one: the cancellation is a version-checked write and the claim advanced the version, so the write is refused. The
 * caller gets a conflict, retries, re-reads a job that is now RUNNING, and records the request instead of cancelling
 * outright. Nothing special had to be written for that — the refusal is the whole mechanism, and it exists only
 * because the claim statement bumps the version. {@code FailureVisibilityTest} measures both halves.
 *
 * <p>Which is why this sample leaves {@code aipersimmon.ddd.cqrs.retry-on-conflict} off. Retrying here would be
 * defensible, and it would also hide from the client the one thing worth knowing: that the job started while they
 * were cancelling it.
 */
public record CancelExport(@NotBlank String exportId) implements Command<Boolean> {}
