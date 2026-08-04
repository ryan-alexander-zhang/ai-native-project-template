package com.example.samples.s10.points.application;

import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s10.points.domain.PointsAccount;
import com.example.samples.s10.points.domain.PointsAccountId;
import com.example.samples.s10.points.domain.PointsAccounts;
import com.example.samples.s10.points.domain.PointsErrorCode;
import com.example.samples.s10.points.domain.ReserveOutcome;
import org.springframework.stereotype.Component;

/**
 * TCC Try, as an ordinary command.
 *
 * <p>The one thing to notice: this commits. Try is a complete local transaction, so by the time the
 * caller's global transaction is still deciding, the points row has already been released and any other
 * writer may take it. That is the whole difference from AT, and it is why {@code frozen} has to exist —
 * the promise must survive in data, because it is not surviving in a lock.
 */
@Component
class ReservePointsHandler implements CommandHandler<ReservePoints, ReserveOutcome> {

  private final PointsAccounts accounts;

  ReservePointsHandler(PointsAccounts accounts) {
    this.accounts = accounts;
  }

  @Override
  public ReserveOutcome handle(ReservePoints command, CommandContext context) {
    PointsAccountId id = new PointsAccountId(command.accountId());
    PointsAccount account =
        accounts
            .find(id, command.reference())
            .orElseThrow(
                () ->
                    new EntityNotFoundException(
                        PointsErrorCode.POINTS_ACCOUNT_NOT_FOUND,
                        "no points account " + command.accountId()));
    ReserveOutcome outcome = account.reserve(command.reference(), command.points());
    if (outcome == ReserveOutcome.RESERVED) {
      accounts.save(account);
    }
    return outcome;
  }
}
