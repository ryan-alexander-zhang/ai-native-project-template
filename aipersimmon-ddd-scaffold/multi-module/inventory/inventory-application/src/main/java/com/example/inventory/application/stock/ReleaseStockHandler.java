package com.example.inventory.application.stock;

import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.core.exception.DomainException;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.inventory.api.StockReleased;
import com.example.inventory.domain.stock.InventoryErrorCode;
import com.example.inventory.domain.stock.Reservation;
import com.example.inventory.domain.stock.ReservationId;
import com.example.inventory.domain.stock.Reservations;
import com.example.inventory.domain.stock.Sku;
import com.example.inventory.domain.stock.Stock;
import com.example.inventory.domain.stock.Stocks;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Handles {@link ReleaseStock}: hands the reserved quantities back to their stock and announces
 * {@link StockReleased}. It is idempotent — a reservation that was already released hands nothing
 * back a second time but still re-announces the event, so a retried or duplicated release converges
 * on the same state rather than double-crediting stock.
 */
@Component
public class ReleaseStockHandler implements CommandHandler<ReleaseStock, Void> {

  private final Stocks stocks;
  private final Reservations reservations;
  private final IntegrationEvents integrationEvents;

  public ReleaseStockHandler(
      Stocks stocks, Reservations reservations, IntegrationEvents integrationEvents) {
    this.stocks = stocks;
    this.reservations = reservations;
    this.integrationEvents = integrationEvents;
  }

  @Override
  public Void handle(ReleaseStock command, CommandContext context) {
    Reservation reservation =
        reservations
            .findById(new ReservationId(command.reservationId()))
            .orElseThrow(
                () ->
                    new DomainException(
                        InventoryErrorCode.RESERVATION_NOT_FOUND,
                        "unknown reservation: " + command.reservationId()));

    // markReleased() flips the flag once; a second ReleaseStock finds it already false and skips
    // the hand-back, but we still publish so the saga's wait for StockReleased always resolves.
    if (reservation.markReleased()) {
      for (Map.Entry<Sku, Integer> line : reservation.held()) {
        Stock stock =
            stocks
                .findBySku(line.getKey())
                .orElseThrow(
                    () ->
                        new DomainException(
                            InventoryErrorCode.STOCK_NOT_FOUND,
                            "unknown sku: " + line.getKey().value()));
        stock.release(line.getValue());
        stocks.save(stock);
      }
      reservations.save(reservation);
    }
    integrationEvents.publish(
        new StockReleased(reservation.orderId(), command.reservationId()), context);
    return null;
  }
}
