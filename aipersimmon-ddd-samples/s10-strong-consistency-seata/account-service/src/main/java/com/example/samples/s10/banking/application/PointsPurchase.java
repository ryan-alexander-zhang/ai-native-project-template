package com.example.samples.s10.banking.application;

import com.aipersimmon.ddd.core.exception.DomainException;
import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.tenancy.TenantContext;
import com.example.samples.s10.banking.domain.BankingErrorCode;
import org.apache.seata.core.context.RootContext;
import org.apache.seata.spring.annotation.GlobalTransactional;
import org.springframework.stereotype.Service;

/**
 * The use case that has to be all-or-nothing: spend money, earn points, in two databases.
 *
 * <p><strong>Where the global transaction boundary goes, and why it is here.</strong> It is one layer
 * <em>above</em> the command bus, and that ordering is not stylistic. The bus's interceptor opens the local
 * transaction; Seata needs that local transaction to begin and end <em>inside</em> the global one, because
 * a branch is exactly one committed local transaction plus its undo log. Put {@code @GlobalTransactional}
 * below the local transaction and there is no branch to undo; put it on the controller and the boundary of
 * a business decision is being defined by a URL.
 *
 * <p>So the stack, outermost first: HTTP → <strong>global transaction</strong> → command bus's local
 * transaction → aggregate. And the debit's local transaction commits — really commits, visibly, to anyone
 * reading that row — well before the customer's request returns. "Strong consistency" here does not mean no
 * intermediate state exists; it means no intermediate state is <em>reachable</em>, because the global lock
 * refuses every other global transaction that would want to see it. A plain, unlocked reader still sees it.
 *
 * <p><strong>Two methods, two protocols, one use case.</strong> They exist side by side so the choice can be
 * measured rather than argued: {@link #purchaseWithAtParticipant} holds the points row locked for the whole
 * business transaction and needs no model changes; {@link #purchaseWithTccParticipant} releases the row at
 * Try and needs {@code frozen} in the participant's model. That is the entire trade.
 */
@Service
public class PointsPurchase {

  private final CommandBus commandBus;
  private final PointsParticipant pointsParticipant;
  private final PointsAwardAction pointsAwardAction;

  public PointsPurchase(
      CommandBus commandBus,
      PointsParticipant pointsParticipant,
      PointsAwardAction pointsAwardAction) {
    this.commandBus = commandBus;
    this.pointsParticipant = pointsParticipant;
    this.pointsAwardAction = pointsAwardAction;
  }

  /**
   * AT: both services write, and the coordinator undoes whichever wrote first if the other fails.
   *
   * <p>{@code rollbackFor} is not set, and does not need to be: Seata rolls back on any {@code Throwable}
   * unless told otherwise, which is the opposite of Spring's {@code @Transactional} default. Two
   * annotations, two defaults, on adjacent lines of the same call stack — worth knowing before relying on
   * either.
   */
  @GlobalTransactional(name = "s10-purchase-at", timeoutMills = 60_000)
  public Receipt purchaseWithAtParticipant(Purchase purchase) {
    commandBus.send(
        new DebitAccount(purchase.accountId(), purchase.amountMinor(), purchase.reference()));
    if (!pointsParticipant.award(
        purchase.reference(), purchase.pointsAccountId(), purchase.points())) {
      throw new DomainException(
          BankingErrorCode.POINTS_REFUSED,
          "the points service refused reference " + purchase.reference());
    }
    hold(purchase.holdMillis());
    // A caller-controlled failure after both writes have committed locally. This is the only interesting
    // moment in the whole sample: both rows are changed, both undo logs are written, nothing is decided.
    if (purchase.thenFail()) {
      throw new IllegalStateException("failing after both branches committed, on purpose");
    }
    return new Receipt(RootContext.getXID(), purchase.reference(), "AT");
  }

  /**
   * TCC: the points are promised, then settled, and the settlement happens after this method returns.
   *
   * <p>Note what the method does <em>not</em> do: it never calls confirm or cancel. It calls Try, and Seata
   * calls the other phase when the global transaction ends — possibly after this thread is gone. Reading
   * this method top to bottom therefore does not tell you what the system does, which is TCC's real cost
   * and the reason the participant's three methods have to be readable on their own.
   */
  @GlobalTransactional(name = "s10-purchase-tcc", timeoutMills = 60_000)
  public Receipt purchaseWithTccParticipant(Purchase purchase) {
    commandBus.send(
        new DebitAccount(purchase.accountId(), purchase.amountMinor(), purchase.reference()));
    boolean promised =
        pointsAwardAction.tryAward(
            null,
            purchase.reference(),
            purchase.pointsAccountId(),
            purchase.points(),
            TenantContext.effective().value());
    if (!promised) {
      throw new DomainException(
          BankingErrorCode.POINTS_REFUSED,
          "the points service refused reference " + purchase.reference());
    }
    hold(purchase.holdMillis());
    if (purchase.thenFail()) {
      throw new IllegalStateException("failing after Try succeeded, on purpose");
    }
    return new Receipt(RootContext.getXID(), purchase.reference(), "TCC");
  }

  /**
   * Stay inside the global transaction, doing nothing, for a while.
   *
   * <p>A sample's affordance with no production purpose, and the only way to observe what a global
   * transaction costs while it is open: under AT another global transaction wanting the same row waits here
   * and then fails; under TCC it does not, because the row was released at Try.
   */
  private static void hold(long millis) {
    if (millis <= 0) {
      return;
    }
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * One purchase.
   *
   * @param accountId the bank account the money leaves
   * @param pointsAccountId the loyalty account the points land in — separate, so two purchases can contend
   *     on the points row without also contending on the same bank account
   * @param thenFail throw after both participants have written
   * @param holdMillis stay inside the global transaction this long before deciding
   */
  public record Purchase(
      String reference,
      String accountId,
      String pointsAccountId,
      long amountMinor,
      int points,
      boolean thenFail,
      long holdMillis) {}

  /** What the caller gets back. The XID is in it because an operator will need it. */
  public record Receipt(String xid, String reference, String mode) {}
}
