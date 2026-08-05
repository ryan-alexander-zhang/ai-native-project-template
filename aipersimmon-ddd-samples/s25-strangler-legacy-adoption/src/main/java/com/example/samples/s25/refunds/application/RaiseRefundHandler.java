package com.example.samples.s25.refunds.application;

import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s25.refunds.api.RefundRaised;
import com.example.samples.s25.refunds.domain.Refund;
import com.example.samples.s25.refunds.domain.RefundErrorCode;
import com.example.samples.s25.refunds.domain.RefundId;
import com.example.samples.s25.refunds.domain.RefundIds;
import com.example.samples.s25.refunds.domain.Refunds;
import org.springframework.stereotype.Component;

/**
 * Reserve an id, read the order through the ACL, let the aggregate decide, publish.
 *
 * <p>Four steps and the order of the first two is forced by the schema rather than chosen: the id has to be reserved
 * before the insert because the library's write path will not insert a row without one, and a legacy {@code BIGSERIAL}
 * would otherwise have assigned it. See {@code RefundIds}.
 *
 * <p>The publish is the answer to "can the outbox feed the new context during the double-write period". It can — for
 * writes that come through here, in this transaction, which is the whole condition. The legacy path cannot use it at
 * all: it has no {@code IntegrationEvents}, no participation in the library's transaction, and no place to put a row.
 * Which is why the honest double-write arrangement is <strong>one writer and two readers</strong>, never two writers —
 * measured in {@code DoubleWriteTest}.
 */
@Component
class RaiseRefundHandler implements CommandHandler<RaiseRefund, Long> {

  private final Refunds refunds;
  private final RefundIds ids;
  private final OrderFacts orders;
  private final IntegrationEvents integrationEvents;

  RaiseRefundHandler(
      Refunds refunds, RefundIds ids, OrderFacts orders, IntegrationEvents integrationEvents) {
    this.refunds = refunds;
    this.ids = ids;
    this.orders = orders;
    this.integrationEvents = integrationEvents;
  }

  @Override
  public Long handle(RaiseRefund command, CommandContext context) {
    OrderFacts.Snapshot order =
        orders
            .of(command.orderId())
            .orElseThrow(
                () ->
                    new EntityNotFoundException(
                        RefundErrorCode.ORDER_NOT_FOUND, "no order " + command.orderId()));
    RefundId id = ids.reserve();
    Refund refund =
        Refund.raise(
            id,
            command.orderId(),
            command.amountCents(),
            command.reason(),
            order.cancelled(),
            order.totalCents(),
            refunds.hasOpenRefund(command.orderId()));
    refunds.save(refund);
    integrationEvents.publish(
        new RefundRaised(
            refund.publicId().toString(), refund.orderId(), refund.amountCents()), context);
    return id.value();
  }
}
