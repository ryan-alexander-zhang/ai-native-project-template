package com.example.ordering.application.order;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.aipersimmon.ddd.core.exception.DomainException;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.ordering.application.fulfilment.FulfilmentTrigger;
import com.example.ordering.application.order.StockAvailabilityGateway.Availability;
import com.example.ordering.domain.customer.CreditExceededException;
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
import java.util.List;
import java.util.UUID;
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

  private static final ManualReviewPolicy REVIEW = new ManualReviewPolicy();

  private final Orders orders;
  private final Customers customers;
  private final DomainEvents domainEvents;
  private final StockAvailabilityGateway stockAvailability;
  private final FulfilmentTrigger fulfilmentTrigger;

  public PlaceOrderHandler(
      Orders orders,
      Customers customers,
      DomainEvents domainEvents,
      StockAvailabilityGateway stockAvailability,
      FulfilmentTrigger fulfilmentTrigger) {
    this.orders = orders;
    this.customers = customers;
    this.domainEvents = domainEvents;
    this.stockAvailability = stockAvailability;
    this.fulfilmentTrigger = fulfilmentTrigger;
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

    List<LineData> lines =
        command.lines().stream()
            .map(
                line ->
                    new LineData(
                        line.sku(),
                        line.quantity(),
                        Money.of(line.unitAmountMinor(), line.currency())))
            .toList();

    OrderId orderId = new OrderId(UUID.randomUUID().toString());
    ReviewRequirement review = REVIEW.assess(lines);
    Order order = Order.place(orderId, customerId, lines, review);

    if (!customer.canAfford(order.total())) {
      throw new CreditExceededException(
          "customer " + customerId.value() + " cannot afford " + order.total());
    }

    if (review.isRequired()) {
      // Held for manual review: record the placement, but reserve nothing until it clears.
      orders.save(order);
      domainEvents.publishAndClear(order);
    } else {
      // Cleared immediately: begin fulfilment and ask inventory to reserve, in this transaction.
      fulfilmentTrigger.begin(order, context);
    }
    return orderId.value();
  }
}
