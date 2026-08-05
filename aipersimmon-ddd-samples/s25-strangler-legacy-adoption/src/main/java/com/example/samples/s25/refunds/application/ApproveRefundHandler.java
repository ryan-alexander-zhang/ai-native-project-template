package com.example.samples.s25.refunds.application;

import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s25.refunds.domain.Refund;
import com.example.samples.s25.refunds.domain.RefundErrorCode;
import com.example.samples.s25.refunds.domain.RefundId;
import com.example.samples.s25.refunds.domain.Refunds;
import org.springframework.stereotype.Component;

/** Approve it, and refuse where the monolith went quiet. */
@Component
class ApproveRefundHandler implements CommandHandler<ApproveRefund, Void> {

  private final Refunds refunds;

  ApproveRefundHandler(Refunds refunds) {
    this.refunds = refunds;
  }

  @Override
  public Void handle(ApproveRefund command, CommandContext context) {
    Refund refund =
        refunds
            .find(new RefundId(command.refundId()))
            .orElseThrow(
                () ->
                    new EntityNotFoundException(
                        RefundErrorCode.REFUND_NOT_FOUND, "no refund " + command.refundId()));
    refund.approve(command.approvedBy());
    refunds.save(refund);
    return null;
  }
}
