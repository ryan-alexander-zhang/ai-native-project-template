package com.example.samples.s25.refunds.application;

import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.aipersimmon.ddd.cqrs.QueryHandler;
import com.example.samples.s25.refunds.domain.Refund;
import com.example.samples.s25.refunds.domain.RefundErrorCode;
import com.example.samples.s25.refunds.domain.RefundId;
import com.example.samples.s25.refunds.domain.Refunds;
import org.springframework.stereotype.Component;

/** One refund, read through the aggregate because there is nothing here a projection would earn. */
@Component
class RefundQueryHandler implements QueryHandler<RefundQuery, RefundView> {

  private final Refunds refunds;

  RefundQueryHandler(Refunds refunds) {
    this.refunds = refunds;
  }

  @Override
  public RefundView handle(RefundQuery query) {
    Refund refund =
        refunds
            .find(new RefundId(query.refundId()))
            .orElseThrow(
                () ->
                    new EntityNotFoundException(
                        RefundErrorCode.REFUND_NOT_FOUND, "no refund " + query.refundId()));
    return new RefundView(
        refund.publicId().toString(),
        refund.id().value(),
        refund.orderId(),
        refund.amountCents(),
        refund.state().name(),
        refund.approvedBy().orElse(null));
  }
}
