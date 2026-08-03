package com.example.samples.s21.inventory.application;

import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s21.inventory.domain.InventoryErrorCode;
import com.example.samples.s21.inventory.domain.StockItem;
import com.example.samples.s21.inventory.domain.StockItems;
import com.example.samples.s21.inventory.domain.StockLocation;
import org.springframework.stereotype.Component;

/**
 * The use case, written once, against one shape.
 *
 * <p>Nothing here mentions a revision, and that is the return on the upcaster chain: three revisions
 * arrive at the boundary and one command arrives here. A consumer that instead handled each revision in
 * its own listener would have this logic three times, or would have a switch on the version in the
 * middle of it — and each future bump would touch the use case again.
 */
@Component
class ReserveStockHandler implements CommandHandler<ReserveStock, Void> {

  private final StockItems stockItems;

  ReserveStockHandler(StockItems stockItems) {
    this.stockItems = stockItems;
  }

  @Override
  public Void handle(ReserveStock command, CommandContext context) {
    for (ReserveStock.Line line : command.lines()) {
      StockLocation location = new StockLocation(line.sku(), command.warehouse());
      StockItem item =
          stockItems
              .find(location)
              .orElseThrow(
                  () ->
                      new EntityNotFoundException(
                          InventoryErrorCode.SKU_NOT_STOCKED,
                          "nothing stocked at " + location.value()));
      item.reserve(line.quantity());
      stockItems.save(item);
    }
    return null;
  }
}
