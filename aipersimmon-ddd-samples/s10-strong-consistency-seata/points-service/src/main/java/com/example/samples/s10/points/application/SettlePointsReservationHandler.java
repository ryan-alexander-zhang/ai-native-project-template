package com.example.samples.s10.points.application;

import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.aipersimmon.ddd.core.exception.DomainException;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s10.points.domain.PointsAccount;
import com.example.samples.s10.points.domain.PointsAccountId;
import com.example.samples.s10.points.domain.PointsAccounts;
import com.example.samples.s10.points.domain.PointsErrorCode;
import com.example.samples.s10.points.domain.SettleOutcome;
import org.springframework.stereotype.Component;

/**
 * TCC Confirm and Cancel.
 *
 * <p>Both directions save unconditionally when the aggregate reports {@code SETTLED}, and Cancel also
 * saves on {@code NOTHING_TO_SETTLE} — because in that case the aggregate has just written the
 * cancellation mark that stops a late Try, and dropping the write would recreate exactly the hazard the
 * mark exists to prevent.
 *
 * <p>Confirm with nothing to confirm is the one case that throws. Seata will retry it forever, which is
 * the correct outcome: a missing reservation for a branch the coordinator saw registered means data was
 * lost, and silently answering "fine" would settle a promise nobody made.
 */
@Component
class SettlePointsReservationHandler
    implements CommandHandler<SettlePointsReservation, SettleOutcome> {

  private final PointsAccounts accounts;

  SettlePointsReservationHandler(PointsAccounts accounts) {
    this.accounts = accounts;
  }

  @Override
  public SettleOutcome handle(SettlePointsReservation command, CommandContext context) {
    PointsAccountId id = new PointsAccountId(command.accountId());
    PointsAccount account =
        accounts
            .find(id, command.reference())
            .orElseThrow(
                () ->
                    new EntityNotFoundException(
                        PointsErrorCode.POINTS_ACCOUNT_NOT_FOUND,
                        "no points account " + command.accountId()));

    boolean cancelling = command.direction() == SettlePointsReservation.Direction.CANCEL;
    SettleOutcome outcome =
        cancelling
            ? account.cancelReservation(command.reference(), command.points())
            : account.confirmReservation(command.reference());

    if (!cancelling && outcome == SettleOutcome.NOTHING_TO_SETTLE) {
      throw new DomainException(
          PointsErrorCode.NOTHING_TO_CONFIRM,
          "confirm arrived for reservation " + command.reference() + ", which is not here");
    }
    if (outcome == SettleOutcome.SETTLED
        || (cancelling && outcome == SettleOutcome.NOTHING_TO_SETTLE)) {
      accounts.save(account);
    }
    return outcome;
  }
}
