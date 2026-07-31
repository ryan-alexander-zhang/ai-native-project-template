package com.example.inventory.application.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.tenancy.Tenants;
import com.aipersimmon.ddd.test.RecordingIntegrationEvents;
import com.example.inventory.api.StockReservationFailed;
import com.example.inventory.domain.stock.Reservation;
import com.example.inventory.domain.stock.ReservationId;
import com.example.inventory.domain.stock.Reservations;
import com.example.inventory.domain.stock.Sku;
import com.example.inventory.domain.stock.Stock;
import com.example.inventory.domain.stock.Stocks;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@code StockReservationFailed.code} is a published contract that promises "a stable
 * machine-readable code" — and the consuming side holds it to that: ordering's {@code
 * ReservationFailureRef} refuses a null or blank {@code reasonCode}, so a codeless failure would
 * poison the fulfilment process's consuming transaction and strand the flow until its deadline,
 * misattributed as a timeout (issue-00131). This pins the producing side of the bargain: whatever
 * the domain throws — including a {@code DomainException} carrying no {@code ErrorCode} — the event
 * leaves inventory with a non-null code.
 */
class StockReservationFailedCodeContractTest {

  private final RecordingIntegrationEvents events = new RecordingIntegrationEvents();

  @Test
  void aCodelessDomainFailureStillPublishesAStableCode() {
    // quantity 0 reaches the handler only when bus validation is bypassed, which is exactly the
    // kind of unforeseen path that produces a codeless DomainException (Stock.reserve refuses
    // non-positive quantities without an ErrorCode).
    ReserveStockHandler handler =
        new ReserveStockHandler(new OneSkuInStock(), new NoReservations(), events, () -> "res-1");

    handler.handle(
        new ReserveStock("order-1", List.of(new ReserveStock.Line("SKU-1", 0))),
        CommandContext.root(Tenants.of("demo"), "msg-1"));

    StockReservationFailed failed = (StockReservationFailed) events.events().get(0);
    assertNotNull(failed.code(), "the contract promises a machine-readable code, never null");
    assertEquals("inventory.unspecified", failed.code());
  }

  private static final class OneSkuInStock implements Stocks {
    @Override
    public Optional<Stock> findBySku(Sku sku) {
      return Optional.of(new Stock(sku, 5));
    }

    @Override
    public void save(Stock stock) {}
  }

  private static final class NoReservations implements Reservations {
    @Override
    public Optional<Reservation> findById(ReservationId id) {
      return Optional.empty();
    }

    @Override
    public void save(Reservation reservation) {}
  }
}
