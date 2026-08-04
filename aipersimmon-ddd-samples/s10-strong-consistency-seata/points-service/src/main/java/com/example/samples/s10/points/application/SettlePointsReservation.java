package com.example.samples.s10.points.application;

import com.aipersimmon.ddd.cqrs.Command;
import com.example.samples.s10.points.domain.SettleOutcome;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * TCC Confirm and Cancel, as one command with a direction.
 *
 * <p>One command rather than two, because they are the same decision seen from both ends and the
 * participant must treat them as mutually exclusive states of one reference. Two commands would have
 * invited two handlers, and two handlers is where "confirm after cancel" stops being obviously wrong.
 *
 * @param points what to record if Cancel finds no reservation at all — Seata's empty rollback. Zero when
 *     the caller does not know, which is legitimate: the mark matters more than the number.
 */
public record SettlePointsReservation(
    @NotBlank String reference,
    @NotBlank String accountId,
    @PositiveOrZero int points,
    Direction direction)
    implements Command<SettleOutcome> {

  public enum Direction {
    CONFIRM,
    CANCEL
  }
}
