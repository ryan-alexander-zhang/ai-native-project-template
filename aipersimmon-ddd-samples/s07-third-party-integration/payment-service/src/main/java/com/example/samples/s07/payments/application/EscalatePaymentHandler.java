package com.example.samples.s07.payments.application;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s07.payments.domain.Payment;
import com.example.samples.s07.payments.domain.PaymentId;
import com.example.samples.s07.payments.domain.Payments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Flags the payment and says so out loud. The log line is not decoration: this is the one transition in
 * the service that no customer and no provider is waiting on, so if it is not written somewhere a person
 * reads, the payment is stuck and nobody knows.
 */
@Component
class EscalatePaymentHandler implements CommandHandler<EscalatePayment, Void> {

  private static final Logger log = LoggerFactory.getLogger(EscalatePaymentHandler.class);

  private final Payments payments;

  EscalatePaymentHandler(Payments payments) {
    this.payments = payments;
  }

  @Override
  public Void handle(EscalatePayment command, CommandContext context) {
    PaymentId id = new PaymentId(command.paymentId());
    Payment payment =
        payments
            .find(id)
            .orElseThrow(() -> new UnknownPaymentReferenceException(command.paymentId()));

    payment.flagForReview(command.reason());
    payments.save(payment);

    log.warn(
        "payment {} needs review: {} (status {}, gatewayRef {})",
        command.paymentId(),
        payment.reviewReason(),
        payment.status(),
        payment.gatewayRef());
    return null;
  }
}
