package com.example.ordering.application.order;

import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.ordering.domain.customer.CustomerId;
import com.example.ordering.domain.order.CancellationReason;
import com.example.ordering.domain.order.Order;
import com.example.ordering.domain.order.OrderId;
import com.example.ordering.domain.order.Orders;
import com.example.ordering.domain.shared.OrderingErrorCode;
import org.springframework.stereotype.Component;

/**
 * Handles {@link CancelOwnOrder}. It loads the order and asks the aggregate to cancel with a {@code
 * CustomerRequested} reason; the aggregate consults {@code OrderLifecyclePolicy}, which refuses
 * with a coded exception when the customer is not the owner or the window has closed.
 *
 * <p>Note what this handler does <em>not</em> do: it does not check eligibility first. Asking the
 * specification here and then asking the aggregate again would be two decisions where the model has
 * one, and the second is the one that counts — anything checked outside the aggregate is checked on
 * a snapshot that a concurrent write can invalidate before the save. The specification's job is to
 * let a client know in advance whether to offer the action ({@code OrderSnapshot.cancellable}); the
 * aggregate's job is to be right at the moment of the write.
 */
@Component
public class CancelOwnOrderHandler implements CommandHandler<CancelOwnOrder, Void> {

  private final Orders orders;

  public CancelOwnOrderHandler(Orders orders) {
    this.orders = orders;
  }

  @Override
  public Void handle(CancelOwnOrder command, CommandContext context) {
    OrderId id = new OrderId(command.orderId());
    Order order =
        orders
            .findById(id)
            .orElseThrow(
                () ->
                    new EntityNotFoundException(
                        OrderingErrorCode.ORDER_NOT_FOUND, "unknown order: " + command.orderId()));

    order.cancel(new CancellationReason.CustomerRequested(new CustomerId(command.customerId())));

    orders.save(order);
    return null;
  }
}
