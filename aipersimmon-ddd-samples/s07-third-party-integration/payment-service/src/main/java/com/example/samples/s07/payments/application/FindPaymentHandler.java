package com.example.samples.s07.payments.application;

import com.aipersimmon.ddd.cqrs.QueryHandler;
import com.example.samples.s07.payments.domain.PaymentId;
import com.example.samples.s07.payments.domain.Payments;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The poller's read. It runs behind the bus, which matters more here than in most samples: a client
 * polls this endpoint while the gateway callback is writing the same row, so the read wants the
 * transaction boundary the bus establishes rather than whatever isolation a bare repository call in
 * a controller happened to get.
 */
@Component
class FindPaymentHandler implements QueryHandler<FindPayment, Optional<PaymentView>> {

  private final Payments payments;

  FindPaymentHandler(Payments payments) {
    this.payments = payments;
  }

  @Override
  public Optional<PaymentView> handle(FindPayment query) {
    return payments
        .find(new PaymentId(query.paymentId()))
        .map(
            payment ->
                new PaymentView(
                    payment.id().value(),
                    payment.orderRef(),
                    payment.amountMinor(),
                    payment.status().name(),
                    payment.gatewayRef(),
                    payment.needsReview(),
                    payment.reviewReason()));
  }
}
