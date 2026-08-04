package com.example.samples.s07.payments.application;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s07.payments.domain.Payment;
import com.example.samples.s07.payments.domain.PaymentId;
import com.example.samples.s07.payments.domain.Payments;
import com.example.samples.s07.payments.domain.SettlementOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Load, apply, save. The handler decides nothing: which of the four outcomes this is belongs to the
 * aggregate, because all four are consequences of the payment's own state.
 *
 * <p>It saves unconditionally, including when nothing changed. That costs one {@code UPDATE} and a
 * version bump per duplicate callback, and the alternative — the handler asking "did anything change?"
 * — would mean reproducing the aggregate's rules outside it in order to avoid a write. At payment
 * volumes the write is cheaper than the duplication. Where two duplicates arrive at once, one loses the
 * optimistic-lock race and is retried by the framework's {@code RetryOnConflict} interceptor; the
 * property that enables it is safe here precisely because no handler in this service performs a
 * non-transactional side effect (see {@code application.yaml}).
 */
@Component
class RecordGatewayResultHandler
    implements CommandHandler<RecordGatewayResult, SettlementOutcome> {

  private static final Logger log = LoggerFactory.getLogger(RecordGatewayResultHandler.class);

  private final Payments payments;

  RecordGatewayResultHandler(Payments payments) {
    this.payments = payments;
  }

  @Override
  public SettlementOutcome handle(RecordGatewayResult command, CommandContext context) {
    PaymentId id = new PaymentId(command.paymentId());
    Payment payment =
        payments.find(id).orElseThrow(() -> new UnknownPaymentReferenceException(command.paymentId()));

    SettlementOutcome outcome =
        payment.recordGatewayResult(command.outcome(), command.gatewayRef());
    payments.save(payment);

    if (outcome != SettlementOutcome.APPLIED) {
      // Worth a line each: SUPERSEDED means deliveries are arriving out of order, DUPLICATE is the
      // at-least-once tax, and CONTRADICTED is a page. Silence here is how an integration's health
      // becomes unknowable.
      log.info(
          "payment {} — {} via {} was {}",
          command.paymentId(),
          command.outcome(),
          command.channel(),
          outcome);
    }
    return outcome;
  }
}
