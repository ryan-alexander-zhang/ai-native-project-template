package com.example.inventory.application.stock;

import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.application.UseCase;
import com.aipersimmon.ddd.core.error.ErrorCode;
import com.aipersimmon.ddd.core.exception.DomainException;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.inventory.api.StockReservationFailed;
import com.example.inventory.api.StockReserved;
import com.example.inventory.domain.stock.InventoryErrorCode;
import com.example.inventory.domain.stock.Reservation;
import com.example.inventory.domain.stock.ReservationId;
import com.example.inventory.domain.stock.Reservations;
import com.example.inventory.domain.stock.Sku;
import com.example.inventory.domain.stock.Stock;
import com.example.inventory.domain.stock.Stocks;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Handles {@link ReserveStock} and announces the outcome: a {@link StockReserved} event on success,
 * or a {@link StockReservationFailed} event if any line cannot be reserved. Reporting failure as an
 * event (rather than throwing) lets the ordering context's saga react to it and compensate. It
 * validates every line before reserving any, so a failure leaves no partial reservation.
 *
 * <p>On success it also records a {@link Reservation} keyed by a freshly minted {@link
 * ReservationId}, and publishes that id on the event. That id is what makes the later release exact
 * and idempotent — the saga hands it back verbatim to release the same stock it reserved.
 *
 * <h2>A deliberate multi-aggregate transaction</h2>
 *
 * <p>One reservation mutates several {@link Stock} aggregates and creates one {@link Reservation}
 * aggregate, and the "reserve every line or none" rule spans all of them. That invariant therefore
 * does <em>not</em> live inside a single aggregate — it is enforced here, by the application
 * transaction the command bus opens around this handler. This is a conscious exception to the "one
 * aggregate per transaction" guideline, made because a {@code Stock} row per SKU is the natural
 * consistency and contention boundary for inventory, and forcing all SKUs into one aggregate would
 * serialise unrelated stock. The all-or-nothing guarantee is real but transactional, not
 * aggregate-level; a distributed inventory would instead model this as its own saga. The
 * validate-all-before-mutate-any loop above is what keeps a mid-line failure from leaving a partial
 * reservation even before the transaction rolls back.
 */
@Component
@UseCase
public class ReserveStockHandler implements CommandHandler<ReserveStock, Void> {

  private final Stocks stocks;
  private final Reservations reservations;
  private final IntegrationEvents integrationEvents;

  public ReserveStockHandler(
      Stocks stocks, Reservations reservations, IntegrationEvents integrationEvents) {
    this.stocks = stocks;
    this.reservations = reservations;
    this.integrationEvents = integrationEvents;
  }

  @Override
  public Void handle(ReserveStock command, CommandContext context) {
    try {
      // Validate all lines first, so a later failure leaves no partial reservation.
      for (ReserveStock.Line line : command.lines()) {
        Stock stock = stockFor(line.sku());
        if (line.quantity() <= 0 || line.quantity() > stock.available()) {
          throw new DomainException(
              InventoryErrorCode.INSUFFICIENT_STOCK,
              "cannot reserve " + line.quantity() + " of " + line.sku());
        }
      }
      Map<Sku, Integer> held = new LinkedHashMap<>();
      for (ReserveStock.Line line : command.lines()) {
        Stock stock = stockFor(line.sku());
        stock.reserve(line.quantity());
        stocks.save(stock);
        held.merge(new Sku(line.sku()), line.quantity(), Integer::sum);
      }
      ReservationId reservationId = new ReservationId(UUID.randomUUID().toString());
      reservations.save(new Reservation(reservationId, command.orderId(), held));
      integrationEvents.publish(
          new StockReserved(command.orderId(), reservationId.value()), context);
    } catch (DomainException failure) {
      // The failing code (if any) rides the event: a BC with no HTTP edge still
      // surfaces a stable machine identity for the reacting saga to branch on.
      String code = failure.errorCode().map(ErrorCode::code).orElse(null);
      integrationEvents.publish(
          new StockReservationFailed(command.orderId(), code, failure.getMessage()), context);
    }
    return null;
  }

  private Stock stockFor(String sku) {
    return stocks
        .findBySku(new Sku(sku))
        .orElseThrow(
            () -> new DomainException(InventoryErrorCode.STOCK_NOT_FOUND, "unknown sku: " + sku));
  }
}
