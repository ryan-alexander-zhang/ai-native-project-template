package com.example.ordering.application.order;

import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.example.ordering.domain.customer.Customer;
import com.example.ordering.domain.customer.Customers;
import com.example.ordering.domain.order.Order;
import com.example.ordering.domain.shared.OrderingErrorCode;
import org.springframework.stereotype.Component;

/**
 * Returns the credit an order was holding, when that order will not be paid for.
 *
 * <p>Credit is committed in one place — {@code PlaceOrderHandler} — but released from several,
 * because an order can end up cancelled by the customer, by a rejected review, by inventory failing
 * to reserve, by a declined payment, or by the payment deadline expiring. Every one of those is a
 * route to {@code Order.cancel}, and every one of them has to give the credit back: miss a single
 * route and the customer is progressively locked out by orders that no longer exist, which is the
 * quietest possible failure — nothing errors, the limit just silently shrinks.
 *
 * <p>So the release lives here rather than being written out at each call site. There are three
 * such sites today ({@link CancelOrderHandler}, {@link CancelOwnOrderHandler}, {@link
 * RejectReviewHandler}) and all three are thin, but between them they cover six business paths. The
 * third arrived after this paragraph promised the next cancellation route exactly one obvious thing
 * to call, and it found one — which is the only evidence that a claim like that is worth anything.
 *
 * <p>Not folded into {@code Order.cancel} itself, tempting as that is: the order aggregate must not
 * reach across into another aggregate's state. Coordinating the two is the application's job, which
 * is the same boundary {@code PlaceOrderHandler} respects on the way in.
 */
@Component
public class CustomerCredit {

  private final Customers customers;

  public CustomerCredit(Customers customers) {
    this.customers = customers;
  }

  /**
   * Release the total of a cancelled order back to its customer.
   *
   * <p>Call this after the order's own state change has been saved, so an order that refuses to
   * cancel (already terminal, say) never releases credit it is still holding.
   */
  public void releaseFor(Order order) {
    Customer customer =
        customers
            .findById(order.customerId())
            .orElseThrow(
                () ->
                    new EntityNotFoundException(
                        OrderingErrorCode.CUSTOMER_NOT_FOUND,
                        "unknown customer: " + order.customerId().value()));

    customer.releaseCredit(order.total());
    customers.save(customer);
  }
}
