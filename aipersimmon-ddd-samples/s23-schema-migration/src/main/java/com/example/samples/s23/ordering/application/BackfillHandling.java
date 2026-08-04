package com.example.samples.s23.ordering.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.constraints.Positive;

/**
 * Decide the handling of up to {@code batchSize} orders that predate the column.
 *
 * <p>A command with a batch size and not a "backfill everything" switch. The caller loops until it returns
 * zero, which is what makes the work restartable, observable and stoppable: each call is one transaction,
 * one page, one number in a log line. S11 is where scheduled and batch entry points are the subject; this is
 * that shape, applied to a migration.
 */
public record BackfillHandling(@Positive int batchSize) implements Command<Integer> {}
