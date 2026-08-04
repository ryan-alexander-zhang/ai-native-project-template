package com.example.samples.s28.reconciliation.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.constraints.NotBlank;

/**
 * Put a failed export back in the queue.
 *
 * <p>An explicit command rather than an automatic retry, and that is the deliberate half of it. A job that
 * failed because the period is malformed will fail identically forever, and an automatic retry turns one
 * visible failure into an invisible loop. Automatic retry is right where the failure is plausibly transient
 * and the work is short — which is exactly the library's outbox relay, and exactly not this.
 *
 * <p>What the sample does provide is that the retry is <em>free of consequences</em>: attempts are counted, the
 * previous artifact is discarded, and progress is forgotten. So an operator retrying blindly is safe, which is
 * the property that makes "let a human decide" a workable policy rather than a burden.
 */
public record RetryExport(@NotBlank String exportId) implements Command<Void> {}
