package com.example.ordering.application.order;

import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.ordering.application.fulfilment.FulfilmentTrigger;
import com.example.ordering.domain.order.Order;
import com.example.ordering.domain.order.OrderId;
import com.example.ordering.domain.order.Orders;
import com.example.ordering.domain.order.ReviewDecisionRef;
import com.example.ordering.domain.shared.OrderingErrorCode;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Handles {@link ApproveReview}: the operator's approval of an order held for manual review. It
 * loads the order, records the approving {@link ReviewDecisionRef} (the aggregate rejects a
 * decision for a different order, or an order not awaiting review), then hands the now-ready order
 * to the {@link FulfilmentTrigger} — the same path a review-free order takes at placement. Approval
 * and beginning fulfilment thus share one transaction and one code path.
 */
@Component
public class ApproveReviewHandler implements CommandHandler<ApproveReview, Void> {

  private final Orders orders;
  private final FulfilmentTrigger fulfilmentTrigger;

  public ApproveReviewHandler(Orders orders, FulfilmentTrigger fulfilmentTrigger) {
    this.orders = orders;
    this.fulfilmentTrigger = fulfilmentTrigger;
  }

  @Override
  public Void handle(ApproveReview command, CommandContext context) {
    OrderId id = new OrderId(command.orderId());
    Order order =
        orders
            .findById(id)
            .orElseThrow(
                () ->
                    new EntityNotFoundException(
                        OrderingErrorCode.ORDER_NOT_FOUND, "unknown order: " + command.orderId()));

    order.approveReview(new ReviewDecisionRef(UUID.randomUUID().toString(), id, true));
    fulfilmentTrigger.begin(order, context);
    return null;
  }
}
