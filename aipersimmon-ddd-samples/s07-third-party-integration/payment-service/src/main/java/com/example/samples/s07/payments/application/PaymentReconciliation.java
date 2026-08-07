package com.example.samples.s07.payments.application;

import com.aipersimmon.ddd.core.id.IdGenerator;
import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.tenancy.TenantContext;
import com.example.samples.s07.payments.application.RecordGatewayResult.Channel;
import com.example.samples.s07.payments.domain.GatewayOutcome;
import com.example.samples.s07.payments.domain.SettlementOutcome;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The channel that exists because the callback may never come.
 *
 * <p>Everything else in this sample is a push: we push a charge request, the provider pushes the
 * outcome. Both pushes are at-least-once, which means both are also at-<em>most</em>-nothing — a
 * provider that dropped a callback while we were restarting will not send it again a week later, and
 * nothing in the push design notices. So the payment sits in {@code SUBMITTED}, the customer's money is
 * gone or is not, and the only cure is to ask.
 *
 * <p><strong>No {@code @Scheduled} here.</strong> The trigger is a separate bean in {@code adapter},
 * for the reason the library gives for splitting its own relay from its relay scheduler: a test, an
 * operator endpoint and a timer then all drive the same method.
 *
 * <p><strong>No lease, and the reason is specific.</strong> S11 argues that work with nothing to version
 * — "call a partner API" — needs a claim before two instances may run it. This round does not, because
 * what it does at the provider is a <em>query</em>: two instances asking the same question get the same
 * answer and pay for two requests. The write that follows is a version-checked transition, so one
 * instance wins and the other is refused. If this round ever re-<em>sent</em> a charge, it would need the
 * claim — and the idempotency key besides.
 *
 * <p><strong>Two cutoffs, not one.</strong> {@code stale-after} is when a payment becomes worth asking
 * about; {@code give-up-after} is when patience runs out and a person is told. One cutoff would force a
 * choice between asking too rarely and escalating too eagerly, and the second of those is the expensive
 * one: an escalation is sticky, so the scan excludes the payment afterwards and a wrongly-raised review
 * item never resolves itself. Both of the "we do not know" answers — still pending, and no record at all —
 * are therefore held until {@code give-up-after}.
 *
 * <p>Which also means {@code stale-after} has a floor: it must exceed the time the outbound channel needs
 * to deliver a charge request, or every round asks the provider about payments it has not been told about
 * yet. That is not hypothetical — the first version of this class escalated exactly those.
 *
 * <p><strong>What it will not do.</strong> It never settles a payment from the absence of an answer.
 * Every branch that does not have a provider outcome in hand escalates or waits — because the cost of
 * guessing is asymmetric in a way that has no engineering answer: marking a charged payment failed ships
 * goods for free, and marking a failed payment charged bills a customer who was not charged.
 */
@Component
public class PaymentReconciliation {

  private static final Logger log = LoggerFactory.getLogger(PaymentReconciliation.class);

  private final StalePayments stalePayments;
  private final GatewayCharges gatewayCharges;
  private final CommandBus commandBus;
  private final IdGenerator idGenerator;
  private final Clock clock;
  private final Duration staleAfter;
  private final Duration giveUpAfter;
  private final int batchSize;

  PaymentReconciliation(
      StalePayments stalePayments,
      GatewayCharges gatewayCharges,
      CommandBus commandBus,
      IdGenerator idGenerator,
      Clock clock,
      @Value("${payments.reconciliation.stale-after:PT30S}") Duration staleAfter,
      @Value("${payments.reconciliation.give-up-after:PT10M}") Duration giveUpAfter,
      @Value("${payments.reconciliation.batch-size:100}") int batchSize) {
    this.stalePayments = stalePayments;
    this.gatewayCharges = gatewayCharges;
    this.commandBus = commandBus;
    this.idGenerator = idGenerator;
    this.clock = clock;
    this.staleAfter = staleAfter;
    this.giveUpAfter = giveUpAfter;
    this.batchSize = batchSize;
  }

  /** One bounded round. Safe to call from anywhere, as often as anyone likes. */
  public ReconciliationReport reconcileOnce() {
    Instant now = clock.instant();
    List<String> candidates = stalePayments.findUnsettled(now.minus(staleAfter), batchSize);
    // The same scan with the older cutoff: the payments that have been waiting long enough that
    // "pending" is no longer an acceptable answer. A subset of the above unless the batch limit cut it
    // short, which is why both scans are bounded the same way and the round is idempotent across ticks.
    Set<String> outOfPatience = new HashSet<>(stalePayments.findUnsettled(now.minus(giveUpAfter), batchSize));

    CommandContext round = CommandContext.root(TenantContext.effective(), idGenerator.newId());
    List<ReconciliationReport.Escalation> escalations = new ArrayList<>();
    int settled = 0;
    int unchanged = 0;
    int unreachable = 0;
    int awaiting = 0;

    for (String paymentId : candidates) {
      GatewayReport report = gatewayCharges.reportFor(paymentId);
      switch (report) {
        case GatewayReport.Reported(GatewayOutcome outcome, String gatewayRef) -> {
          if (outcome == GatewayOutcome.ACCEPTED && outOfPatience.contains(paymentId)) {
            escalations.add(
                escalate(
                    paymentId, "the gateway has still not decided after " + giveUpAfter, round));
          } else {
            SettlementOutcome applied =
                commandBus.send(
                    new RecordGatewayResult(paymentId, outcome, gatewayRef, Channel.RECONCILIATION),
                    round);
            if (applied == SettlementOutcome.APPLIED) {
              settled++;
            } else {
              unchanged++;
            }
          }
        }
        case GatewayReport.NoRecord() -> {
          // "I have never heard of this" is only alarming once we have waited. Before the deadline it is
          // the expected answer for a payment whose charge request is still sitting in our own outbox, or
          // in flight — and escalating that is worse than useless: escalations are sticky, so the scan
          // then excludes the payment forever and the review item never resolves itself. This branch was
          // written the eager way first, and a test that ran both schedules for real caught it.
          if (outOfPatience.contains(paymentId)) {
            escalations.add(escalate(paymentId, "the gateway has no record of this payment", round));
          } else {
            awaiting++;
          }
        }
        case GatewayReport.Unintelligible(String detail) ->
            escalations.add(
                escalate(paymentId, "the gateway answered unintelligibly: " + detail, round));
        case GatewayReport.Unreachable(String detail) -> {
          // Nothing to decide and nothing to record: the provider is down or slow, which is a fact
          // about now and not about the payment. The next round asks again.
          unreachable++;
          log.info("payment {} could not be reconciled: {}", paymentId, detail);
        }
      }
    }

    ReconciliationReport report =
        new ReconciliationReport(
            round.correlationId(),
            candidates.size(),
            settled,
            unchanged,
            escalations,
            unreachable,
            awaiting);
    log.info(
        "reconciliation {} scanned {} settled {} unchanged {} escalated {} unreachable {} awaiting {}",
        report.runId(),
        report.scanned(),
        report.settled(),
        report.unchanged(),
        report.escalated().size(),
        report.unreachable(),
        report.awaiting());
    return report;
  }

  private ReconciliationReport.Escalation escalate(
      String paymentId, String reason, CommandContext round) {
    commandBus.send(new EscalatePayment(paymentId, reason), round);
    return new ReconciliationReport.Escalation(paymentId, reason);
  }
}
