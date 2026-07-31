package com.example.ordering.application.order;

import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.aipersimmon.ddd.core.id.IdGenerator;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.ordering.application.fulfilment.FulfilmentTrigger;
import com.example.ordering.domain.order.Order;
import com.example.ordering.domain.order.OrderId;
import com.example.ordering.domain.order.Orders;
import com.example.ordering.domain.order.ReviewDecisionRef;
import com.example.ordering.domain.shared.OrderingErrorCode;
import org.springframework.stereotype.Component;

/**
 * Handles {@link ApproveReview}: the operator's approval of an order held for manual review. It
 * loads the order, records the approving {@link ReviewDecisionRef} (the aggregate rejects a
 * decision for a different order, or an order not awaiting review), then hands the now-ready order
 * to the {@link FulfilmentTrigger} — the same path a review-free order takes at placement. Approval
 * and beginning fulfilment thus share one transaction and one code path.
 *
 * <p>The decision id comes from {@link IdGenerator}, not {@code UUID.randomUUID()}. This one is not
 * a primary key — it is evidence carried into the aggregate, never indexed — so the time-ordering
 * itself buys nothing here. Minting it the same way as every other identifier is the point: one way
 * to make an id, so nobody has to decide per call site which way applies (decision-00019).
 */
@Component
public class ApproveReviewHandler implements CommandHandler<ApproveReview, Void> {

  private final Orders orders;
  private final FulfilmentTrigger fulfilmentTrigger;
  private final IdGenerator idGenerator;

  public ApproveReviewHandler(
      Orders orders, FulfilmentTrigger fulfilmentTrigger, IdGenerator idGenerator) {
    this.orders = orders;
    this.fulfilmentTrigger = fulfilmentTrigger;
    this.idGenerator = idGenerator;
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

    order.approveReview(new ReviewDecisionRef.Approval(idGenerator.newId(), id));
    fulfilmentTrigger.begin(order, context);
    return null;
  }
}
