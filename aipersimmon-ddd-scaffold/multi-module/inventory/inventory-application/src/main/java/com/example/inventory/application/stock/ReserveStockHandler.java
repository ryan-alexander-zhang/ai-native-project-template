package com.example.inventory.application.stock;

import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.core.error.ErrorCode;
import com.aipersimmon.ddd.core.exception.DomainException;
import com.aipersimmon.ddd.core.id.IdGenerator;
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
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Handles {@link ReserveStock} and announces the outcome: a {@link StockReserved} event on success,
 * or a {@link StockReservationFailed} event if any line cannot be reserved. Reporting failure as an
 * event (rather than throwing) lets the ordering context's process manager react to it and
 * compensate.
 *
 * <h2>Decide, then write</h2>
 *
 * <p>The handler runs in two phases with a hard line between them: it loads every {@link Stock} it
 * needs and applies every line <em>in memory</em>, and only once no decision is left does it write
 * anything. A business failure therefore happens while the transaction is still clean, and the
 * failure event is the only thing that commits.
 *
 * <p>That split is load-bearing, not tidiness. Reporting failure as an event and using exceptions
 * to control the transaction are two mechanisms that want opposite things from a {@code
 * DomainException}: the cross-context protocol needs it turned into an event, Spring's declarative
 * transaction needs it to escape. This code has to choose the protocol — so it must not have any
 * uncommitted change left to undo at the moment it chooses. It previously did: lines were validated
 * up front and then re-loaded and saved one at a time, and because a save is visible to the re-load
 * that follows it, a later line could fail against stock the earlier lines had already consumed.
 * The catch then swallowed the exception, the transaction committed, and the deducted quantity was
 * stranded — no {@link Reservation} existed, so nothing could ever release it (issue-00094).
 *
 * <p>The other half of the same rule: <strong>one aggregate is loaded at most once per
 * transaction</strong>. Two instances of one {@code Stock} are two writers racing inside a single
 * transaction, each unaware of the other's deduction. The map below makes that structural rather
 * than incidental, and {@link ReserveStock} merges lines repeating a SKU before the handler ever
 * sees them, so inventory no longer depends on ordering's {@code OrderHasDistinctSkus} to stay
 * correct (issue-00076).
 *
 * <p>On success it also records a {@link Reservation} keyed by a freshly minted {@link
 * ReservationId}, and publishes that id on the event. That id is what makes the later release exact
 * and idempotent — the process manager hands it back verbatim to release the same stock it
 * reserved.
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
 * aggregate-level; a distributed inventory would instead model this as its own process manager.
 *
 * <p>Its boundary condition is the "loaded at most once" rule above. A multi-aggregate transaction
 * is only all-or-nothing if each aggregate in it has exactly one in-memory representative; the
 * moment one appears twice, the transaction is racing itself and neither the version check nor the
 * rollback means what it appears to mean.
 */
@Component
public class ReserveStockHandler implements CommandHandler<ReserveStock, Void> {

  private final Stocks stocks;
  private final Reservations reservations;
  private final IntegrationEvents integrationEvents;
  private final IdGenerator idGenerator;

  public ReserveStockHandler(
      Stocks stocks,
      Reservations reservations,
      IntegrationEvents integrationEvents,
      IdGenerator idGenerator) {
    this.stocks = stocks;
    this.reservations = reservations;
    this.integrationEvents = integrationEvents;
    this.idGenerator = idGenerator;
  }

  @Override
  public Void handle(ReserveStock command, CommandContext context) {
    // Decide once, announce every time (issue-00147) — the same contract payment keeps for its
    // paymentOperationId, for the same reason: repeating this action does not do compensable work
    // twice, it leaks a resource. A duplicate outside the inbox's retention window reaches this
    // handler, and without this lookup it would deduct the stock again and write a second
    // Reservation that nothing will ever release (the flow has moved on; the second StockReserved
    // is ignored). So a redelivery finds the reservation already held for the order — released or
    // not — and re-announces it verbatim; the announcement repeats because the previous one may
    // never have arrived. Two deliveries racing past this lookup are settled by the schema's
    // unique (tenant_id, order_id) key: the losing transaction rolls back and its retry lands
    // here, finding the winner's row.
    Optional<Reservation> existing = reservations.findByOrderId(command.orderId());
    if (existing.isPresent()) {
      integrationEvents.publish(
          new StockReserved(command.orderId(), existing.get().id().value()), context);
      return null;
    }

    Map<Sku, Stock> reserved = new LinkedHashMap<>();
    Map<Sku, Integer> held = new LinkedHashMap<>();
    try {
      // DECIDE. Every load and every domain decision happens here, and nothing is written. Each
      // SKU is loaded at most once and mutated in memory, so the aggregate that answers "is there
      // enough left?" is the same object that already absorbed this command's earlier lines.
      for (ReserveStock.Line line : command.lines()) {
        Sku sku = new Sku(line.sku());
        Stock stock = reserved.computeIfAbsent(sku, this::stockFor);
        stock.reserve(line.quantity());
        held.merge(sku, line.quantity(), Integer::sum);
      }
    } catch (DomainException failure) {
      // Nothing has been written, so there is nothing to undo and no reason to roll back: the
      // transaction commits carrying only the failure event. That is the whole point of deciding
      // before writing — see the class javadoc.
      //
      // The failing code rides the event: a BC with no HTTP edge still surfaces a stable machine
      // identity for the reacting process manager to branch on. Never null — the contract promises
      // a code and the consuming side enforces the promise (ordering's ReservationFailureRef
      // refuses a null one), so a codeless DomainException falls back to UNSPECIFIED instead of
      // poisoning the consumer's transaction (issue-00131).
      String code =
          failure.errorCode().map(ErrorCode::code).orElse(InventoryErrorCode.UNSPECIFIED.code());
      integrationEvents.publish(
          new StockReservationFailed(command.orderId(), code, failure.getMessage()), context);
      return null;
    }

    // WRITE. No decision is left to make, so no DomainException can arrive from here — anything
    // that does throw is technical (an optimistic-lock conflict against a concurrent writer) and
    // is deliberately NOT caught: it must escape so the transaction rolls back and the delivery is
    // retried. Catching it here would commit exactly the partial deduction this split prevents.
    for (Stock stock : reserved.values()) {
      stocks.save(stock);
    }
    // Time-ordered (UUIDv7) primary key from IdGenerator, not UUID.randomUUID() (issue-00054).
    ReservationId reservationId = new ReservationId(idGenerator.newId());
    reservations.save(new Reservation(reservationId, command.orderId(), held));
    integrationEvents.publish(new StockReserved(command.orderId(), reservationId.value()), context);
    return null;
  }

  private Stock stockFor(Sku sku) {
    return stocks
        .findBySku(sku)
        .orElseThrow(
            () ->
                new DomainException(
                    InventoryErrorCode.STOCK_NOT_FOUND, "unknown sku: " + sku.value()));
  }
}
