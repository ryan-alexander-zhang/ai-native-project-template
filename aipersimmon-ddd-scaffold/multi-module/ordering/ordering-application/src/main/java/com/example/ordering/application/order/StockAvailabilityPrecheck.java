package com.example.ordering.application.order;

import com.aipersimmon.ddd.core.exception.DomainException;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandPrecheck;
import com.example.ordering.application.order.StockAvailabilityGateway.Availability;
import com.example.ordering.domain.shared.OrderingErrorCode;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Fails a hopeless {@link PlaceOrder} fast — before its transaction opens — by synchronously asking
 * the inventory context (through the {@link StockAvailabilityGateway} anti-corruption port) whether
 * it can currently offer the ordered SKUs at all.
 *
 * <p>This used to be the first thing {@code PlaceOrderHandler} did, which put a cross-context call
 * inside the write transaction: harmless while inventory answers in-process, but the gateway's own
 * javadoc promises the same interface will one day be an HTTP client — and then the advisory check
 * would hold a database connection hostage to a remote call (issue-00141). As a {@link
 * CommandPrecheck} it runs in the bus's precheck slot, after validation and before the transaction
 * interceptor, so a slow inventory answer costs no connection and a refusal costs no transaction.
 *
 * <p>Still deliberately a <em>read</em>, and advisory by construction: the authoritative stock
 * <em>reservation</em> is a compensable state change that happens asynchronously once the order is
 * ready for fulfilment. This check only spares the customer (and the flow) the round trip for
 * orders inventory already knows it cannot serve; stock that disappears between this answer and the
 * reservation is the reservation's problem, exactly as before.
 */
@Component
public class StockAvailabilityPrecheck implements CommandPrecheck<PlaceOrder> {

  private final StockAvailabilityGateway stockAvailability;

  public StockAvailabilityPrecheck(StockAvailabilityGateway stockAvailability) {
    this.stockAvailability = stockAvailability;
  }

  @Override
  public void check(PlaceOrder command, CommandContext context) {
    List<String> skus = command.lines().stream().map(PlaceOrder.Line::sku).distinct().toList();
    Availability availability = stockAvailability.check(skus);
    if (!availability.allAvailable()) {
      throw new DomainException(
          OrderingErrorCode.STOCK_UNAVAILABLE,
          "inventory cannot currently offer: " + availability.unavailableSkus());
    }
  }
}
