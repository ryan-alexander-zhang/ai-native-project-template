package com.example.ordering.application.order;

import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.aipersimmon.ddd.core.exception.DomainException;
import com.aipersimmon.ddd.core.id.IdGenerator;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.ordering.application.fulfilment.FulfilmentTrigger;
import com.example.ordering.application.order.StockAvailabilityGateway.Availability;
import com.example.ordering.domain.customer.Customer;
import com.example.ordering.domain.customer.CustomerId;
import com.example.ordering.domain.customer.Customers;
import com.example.ordering.domain.order.LineData;
import com.example.ordering.domain.order.ManualReviewPolicy;
import com.example.ordering.domain.order.Order;
import com.example.ordering.domain.order.OrderId;
import com.example.ordering.domain.order.Orders;
import com.example.ordering.domain.order.ReviewRequirement;
import com.example.ordering.domain.shared.Money;
import com.example.ordering.domain.shared.OrderingErrorCode;
import com.example.ordering.domain.shared.Sku;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Handles {@link PlaceOrder}: builds the aggregate, checks the customer's credit, then persists and
 * publishes. It is dispatched by the command bus, which applies the cross-cutting concerns
 * (logging, and — where a transaction manager is present — the transaction) around it.
 *
 * <p>Before creating anything it calls the {@link StockAvailabilityGateway} — a synchronous,
 * cross-context query into the inventory context — to fail fast on an order whose SKUs inventory
 * cannot currently offer. This is deliberately a <em>read</em>: the authoritative stock
 * <em>reservation</em> is a state change and happens only once the order is <em>ready for
 * fulfilment</em>, via the {@link com.example.ordering.api.OrderReadyForFulfilment} integration
 * event. The two are complementary — the query gives an immediate, user-facing rejection for
 * hopeless orders; the event does the atomic, compensable reservation for everything that clears.
 *
 * <p>A {@link ManualReviewPolicy} classifies the order: one needing review starts {@code
 * AWAITING_REVIEW} and reserves nothing until an operator approves it (see {@code
 * ApproveReviewHandler}); one that needs no review is ready immediately, so it enters fulfilment
 * now through the {@link FulfilmentTrigger}. Either way, "placed" and "ready for fulfilment" are
 * distinct facts — only readiness drives inventory and the process manager.
 */
@Component
public class PlaceOrderHandler implements CommandHandler<PlaceOrder, String> {

  private final Orders orders;
  private final Customers customers;
  private final IdGenerator idGenerator;
  private final StockAvailabilityGateway stockAvailability;
  private final FulfilmentTrigger fulfilmentTrigger;

  /**
   * Injected, not instantiated. This was a {@code private static final new ManualReviewPolicy()},
   * which made the context's most business-variable rule the one thing a consuming project could
   * not replace without editing this class.
   */
  private final ManualReviewPolicy review;

  public PlaceOrderHandler(
      Orders orders,
      Customers customers,
      IdGenerator idGenerator,
      StockAvailabilityGateway stockAvailability,
      FulfilmentTrigger fulfilmentTrigger,
      ManualReviewPolicy review) {
    this.orders = orders;
    this.customers = customers;
    this.idGenerator = idGenerator;
    this.stockAvailability = stockAvailability;
    this.fulfilmentTrigger = fulfilmentTrigger;
    this.review = review;
  }

  @Override
  public String handle(PlaceOrder command, CommandContext context) {
    CustomerId customerId = new CustomerId(command.customerId());
    Customer customer =
        customers
            .findById(customerId)
            .orElseThrow(
                () ->
                    new EntityNotFoundException(
                        OrderingErrorCode.CUSTOMER_NOT_FOUND,
                        "unknown customer: " + command.customerId()));

    // Fail fast: synchronously ask the inventory context (through the anti-corruption
    // gateway) whether it can offer these SKUs at all, before creating the order. The
    // authoritative quantity reservation still happens asynchronously once the order is ready.
    List<String> skus = command.lines().stream().map(PlaceOrder.Line::sku).distinct().toList();
    Availability availability = stockAvailability.check(skus);
    if (!availability.allAvailable()) {
      throw new DomainException(
          OrderingErrorCode.STOCK_UNAVAILABLE,
          "inventory cannot currently offer: " + availability.unavailableSkus());
    }

    // The boundary where primitives become the context's own types: a command carries strings and
    // longs because that is what arrives over HTTP or a bus, and the domain takes Sku and Money.
    List<LineData> lines =
        command.lines().stream()
            .map(
                line ->
                    new LineData(
                        new Sku(line.sku()),
                        line.quantity(),
                        Money.of(line.unitAmountMinor(), line.currency())))
            .toList();

    // The aggregate's primary key comes from IdGenerator (UUIDv7), not UUID.randomUUID(): orders is
    // the highest-volume table here, so a time-ordered key is worth most on it (issue-00054).
    OrderId orderId = new OrderId(idGenerator.newId());
    ReviewRequirement reviewRequirement = review.assess(lines);
    Order order = Order.place(orderId, customerId, lines, reviewRequirement);

    // Commit the order's total against the customer's credit, in this transaction, alongside the
    // order itself. A deliberate two-aggregate write, and the same trade-off ReserveStockHandler
    // argues for stock: the invariant spans Customer and Order, both live in this database, so it
    // is held in one unit of work rather than chased afterwards with a compensation flow.
    //
    // This is a choice and it has an alternative — treat the check as advisory and reconcile
    // over-limit orders later, which is closer to how real credit systems behave. It was not taken
    // because eventual consistency here would be manufactured: nothing forces these two aggregates
    // apart, and an unreconciled "advisory" check is what issue-00071 found — a rule presented as
    // enforced (its own error code, its own problem type, a top-up flow) with nothing enforcing it.
    //
    // reserveCredit refuses by throwing CreditExceededException; the version check on the customer
    // row is what makes two concurrent placements conflict rather than both succeed.
    customer.reserveCredit(order.total());
    customers.save(customer);

    if (reviewRequirement.isRequired()) {
      // Held for manual review: record the placement, but reserve nothing until it clears. The
      // repository drains the recorded events as part of saving (issue-00052).
      orders.save(order);
    } else {
      // Cleared immediately: begin fulfilment and ask inventory to reserve, in this transaction.
      fulfilmentTrigger.begin(order, context);
    }
    return orderId.value();
  }
}
