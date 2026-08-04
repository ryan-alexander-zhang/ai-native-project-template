package com.example.samples.s22.inventory.application;

import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s22.inventory.domain.InventoryErrorCode;
import com.example.samples.s22.inventory.domain.Sku;
import com.example.samples.s22.inventory.domain.StockItem;
import com.example.samples.s22.inventory.domain.StockItems;
import org.springframework.stereotype.Component;

/**
 * At-least-once delivery, at-most-once effect — and no inbox check here, because the bridge has already
 * made it before this method was called (S4 explains that at length, and had the bug).
 *
 * <p>What S22 adds is the failure taxonomy this handler feeds. Whatever this method throws is what the
 * consumer's error handler has to classify, and it can only classify what it can distinguish:
 *
 * <ul>
 *   <li>a {@code DomainException} — a rule refused, the record was understood. Retrying will refuse
 *       identically, which is why this class of failure ends at the dead-letter topic after its bounded
 *       retries rather than blocking the partition.
 *   <li>a {@code DataAccessException} — the environment is down. Retried indefinitely and
 *       <strong>never</strong> dead-lettered, so an outage does not flush a healthy backlog into the DLT.
 *   <li>anything else — ambiguous, so bounded retries and then the DLT, as a safety net.
 * </ul>
 *
 * <p>The distinction is not a nicety. Treating an outage as poison empties a partition into the DLT in
 * seconds and turns a ten-minute database blip into a manual replay of everything that arrived during
 * it; treating poison as an outage stops the partition forever. The library takes the second risk
 * deliberately for the one case it can identify with certainty, and {@code SystemicFailureTest} measures
 * both halves.
 */
@Component
class ReserveStockHandler implements CommandHandler<ReserveStock, Void> {

  private final StockItems stockItems;

  ReserveStockHandler(StockItems stockItems) {
    this.stockItems = stockItems;
  }

  @Override
  public Void handle(ReserveStock command, CommandContext context) {
    Sku sku = new Sku(command.sku());
    StockItem item =
        stockItems
            .findBySku(sku)
            .orElseThrow(
                () ->
                    new EntityNotFoundException(
                        InventoryErrorCode.SKU_NOT_STOCKED, "sku " + sku.value() + " not stocked"));
    item.reserve(command.quantity());
    stockItems.save(item);
    return null;
  }
}
