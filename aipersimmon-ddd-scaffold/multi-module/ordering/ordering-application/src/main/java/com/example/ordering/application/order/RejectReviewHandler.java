package com.example.ordering.application.order;

import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.aipersimmon.ddd.core.id.IdGenerator;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.ordering.domain.order.CancellationReason;
import com.example.ordering.domain.order.Order;
import com.example.ordering.domain.order.OrderId;
import com.example.ordering.domain.order.Orders;
import com.example.ordering.domain.order.ReviewDecisionRef;
import com.example.ordering.domain.shared.OrderingErrorCode;
import org.springframework.stereotype.Component;

/**
 * Handles {@link RejectReview}: the operator's refusal of an order held for manual review. It mints
 * the deciding {@link ReviewDecisionRef} — the same evidence {@link ApproveReviewHandler} records,
 * with {@code approved = false} — and hands it to the aggregate as a {@code ReviewRejected}
 * cancellation. The aggregate refuses a decision belonging to another order, or an order not
 * awaiting review.
 *
 * <p>The credit release is the part worth pointing at. Rejecting a review is the third route to
 * {@code Order.cancel} in this application, and every route has to give back the credit that
 * placement committed — miss one and the customer's limit shrinks silently, with nothing to see in
 * a log. {@link CustomerCredit} exists so that this handler has exactly one obvious thing to call
 * rather than a paragraph to remember, and its javadoc named a rejected review as a route before
 * this route existed.
 */
@Component
public class RejectReviewHandler implements CommandHandler<RejectReview, Void> {

  private final Orders orders;
  private final CustomerCredit credit;
  private final IdGenerator idGenerator;

  public RejectReviewHandler(Orders orders, CustomerCredit credit, IdGenerator idGenerator) {
    this.orders = orders;
    this.credit = credit;
    this.idGenerator = idGenerator;
  }

  @Override
  public Void handle(RejectReview command, CommandContext context) {
    OrderId id = new OrderId(command.orderId());
    Order order =
        orders
            .findById(id)
            .orElseThrow(
                () ->
                    new EntityNotFoundException(
                        OrderingErrorCode.ORDER_NOT_FOUND, "unknown order: " + command.orderId()));

    order.cancel(
        new CancellationReason.ReviewRejected(
            new ReviewDecisionRef(idGenerator.newId(), id, false)));

    // Saved first, then released: an order that refuses to cancel must not hand back credit it is
    // still holding (CustomerCredit.releaseFor says so, and the ordering is the reason it does).
    orders.save(order);
    credit.releaseFor(order);
    return null;
  }
}
