package com.example.samples.s24.ordering.application;

import com.aipersimmon.ddd.application.DuplicateEntityException;
import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s24.coupons.api.CouponCode;
import com.example.samples.s24.coupons.api.CouponQuote;
import com.example.samples.s24.coupons.api.CouponQuotes;
import com.example.samples.s24.ordering.api.OrderPlaced;
import com.example.samples.s24.ordering.domain.Order;
import com.example.samples.s24.ordering.domain.OrderId;
import com.example.samples.s24.ordering.domain.OrderLine;
import com.example.samples.s24.ordering.domain.Orders;
import com.example.samples.s24.sharedkernel.api.Money;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The one place in ordering that knows the coupons context exists.
 *
 * <p>One class, one dependency, one method call — and that concentration is deliberate. The interesting question about a
 * new context's first integration is not which mechanism to use but <em>how many places will know about it</em>. Here
 * the answer is one, which means the day it becomes a network call there is one place to add a timeout, one place to
 * decide what happens when there is no answer, and one place to read to find out what ordering assumes about coupons.
 *
 * <p><strong>The quote is a call, not an event, because the answer is an input to a decision being made right now.</strong>
 * An order cannot be priced without it. No message arriving later can inform a choice already made, so there is no
 * asynchronous version of this step — which is the useful way to decide the question the catalogue asks. The redemption,
 * by contrast, is a consequence: nobody is waiting for it, and it must not be able to fail the order, so it is a fact
 * published here and acted on after commit.
 *
 * <p><strong>A refused quote is not an error.</strong> An unknown or expired code prices the order at full price and the
 * refusal travels back to the caller as information. Throwing would make a customer's typo an exception, and — more to
 * the point — would put a foreign context's failure in charge of whether this one's use case succeeds.
 *
 * <p>Two things about this method are latent coupling rather than bugs, and the sample names them because they are
 * exactly what the catalogue's last question is about:
 *
 * <ol>
 *   <li>the quote happens <strong>inside this handler's write transaction</strong>, because in-process it is free.
 *       Across a network it would be a remote call holding a database transaction open. {@code SplittingOutTest} measures
 *       that it is inside one today;
 *   <li>the discount is recorded on the order and the redemption is counted afterwards, so between commit and the
 *       after-commit listener the two contexts disagree. That window is the price of not holding anything at quote time,
 *       and its consequence is measured rather than asserted — see {@code QuoteAndRedeemTest}.
 * </ol>
 */
@Component
class PlaceOrderHandler implements CommandHandler<PlaceOrder, OrderTotals> {

  private final Orders orders;
  private final CouponQuotes coupons;
  private final IntegrationEvents integrationEvents;
  private final Clock clock;

  PlaceOrderHandler(
      Orders orders, CouponQuotes coupons, IntegrationEvents integrationEvents, Clock clock) {
    this.orders = orders;
    this.coupons = coupons;
    this.integrationEvents = integrationEvents;
    this.clock = clock;
  }

  @Override
  public OrderTotals handle(PlaceOrder command, CommandContext context) {
    OrderId id = new OrderId(command.orderId());
    if (orders.find(id).isPresent()) {
      throw new DuplicateEntityException("order " + id + " already exists");
    }
    List<OrderLine> lines = linesOf(command);
    Money gross = grossOf(lines);

    // The one call across the boundary. Parsing the code here rather than in the aggregate is what keeps
    // ordering.domain free of coupons.api — the value is validated where it arrives.
    String couponCode = null;
    Money discount = Money.zero(gross.currency());
    String refusal = null;
    if (command.couponCode() != null && !command.couponCode().isBlank()) {
      CouponQuote quote = coupons.quote(new CouponCode(command.couponCode()), gross);
      if (quote.accepted()) {
        couponCode = quote.code().value();
        discount = quote.discount();
      } else {
        refusal = quote.reason();
      }
    }

    Order order =
        Order.place(id, command.customerId(), lines, couponCode, discount, clock.instant());
    orders.save(order);
    integrationEvents.publish(
        new OrderPlaced(
            id.value(),
            order.customerId(),
            order.gross().minor(),
            order.discount().minor(),
            order.gross().currency(),
            couponCode),
        context);
    return new OrderTotals(
        id.value(),
        order.gross().minor(),
        order.discount().minor(),
        order.total().minor(),
        order.gross().currency(),
        couponCode,
        refusal);
  }

  private static List<OrderLine> linesOf(PlaceOrder command) {
    List<OrderLine> lines = new ArrayList<>();
    int lineNo = 1;
    for (PlaceOrder.Line line : command.lines()) {
      lines.add(
          new OrderLine(
              lineNo++,
              line.sku(),
              line.quantity(),
              Money.of(line.unitMinor(), command.currency())));
    }
    return lines;
  }

  private static Money grossOf(List<OrderLine> lines) {
    Money sum = Money.zero(lines.get(0).unitPrice().currency());
    for (OrderLine line : lines) {
      sum = sum.plus(line.lineTotal());
    }
    return sum;
  }
}
