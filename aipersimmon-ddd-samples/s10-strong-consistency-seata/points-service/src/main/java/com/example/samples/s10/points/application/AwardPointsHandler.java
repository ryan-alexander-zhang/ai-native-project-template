package com.example.samples.s10.points.application;

import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s10.points.domain.AwardOutcome;
import com.example.samples.s10.points.domain.PointsAccount;
import com.example.samples.s10.points.domain.PointsAccountId;
import com.example.samples.s10.points.domain.PointsAccounts;
import com.example.samples.s10.points.domain.PointsErrorCode;
import org.springframework.stereotype.Component;

/**
 * Award the points and return.
 *
 * <p>No {@code @Transactional} and no mention of a global transaction: the bus's interceptor opens the
 * local transaction, and Seata has already turned that local transaction into a branch of whatever global
 * transaction the caller's XID named. The handler is written exactly as it would be if there were no
 * distributed transaction at all — which is the property AT is bought for and the reason this file is
 * short.
 */
@Component
class AwardPointsHandler implements CommandHandler<AwardPoints, AwardOutcome> {

  private final PointsAccounts accounts;

  AwardPointsHandler(PointsAccounts accounts) {
    this.accounts = accounts;
  }

  @Override
  public AwardOutcome handle(AwardPoints command, CommandContext context) {
    PointsAccountId id = new PointsAccountId(command.accountId());
    PointsAccount account =
        accounts
            .find(id, command.reference())
            .orElseThrow(
                () ->
                    new EntityNotFoundException(
                        PointsErrorCode.POINTS_ACCOUNT_NOT_FOUND,
                        "no points account " + command.accountId()));
    AwardOutcome outcome = account.award(command.reference(), command.points());
    if (outcome == AwardOutcome.AWARDED) {
      accounts.save(account);
    }
    return outcome;
  }
}
