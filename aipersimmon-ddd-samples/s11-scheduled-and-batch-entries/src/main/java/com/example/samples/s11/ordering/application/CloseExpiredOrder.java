package com.example.samples.s11.ordering.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.constraints.NotBlank;

/**
 * Close one order. One order, not a batch — the granularity is the aggregate, because that is the
 * transaction boundary, the unit of failure and the unit of retry.
 *
 * <p>It carries no "because it expired" flag and no deadline: the aggregate already knows its own
 * deadline, and a command that told it what to conclude would move the rule out of the aggregate and
 * into whoever built the command.
 */
public record CloseExpiredOrder(@NotBlank String orderId) implements Command<Void> {}
