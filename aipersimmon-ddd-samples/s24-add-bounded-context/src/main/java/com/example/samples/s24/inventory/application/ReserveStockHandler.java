package com.example.samples.s24.inventory.application;

import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.core.error.ErrorCategory;
import com.aipersimmon.ddd.core.error.ErrorCode;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s24.inventory.api.StockReserved;
import com.example.samples.s24.inventory.domain.Sku;
import com.example.samples.s24.inventory.domain.StockItem;
import com.example.samples.s24.inventory.domain.StockItems;
import org.springframework.stereotype.Component;

/** Reserve, or say there is not enough. */
@Component
class ReserveStockHandler implements CommandHandler<ReserveStock, Boolean> {

  private final StockItems stock;
  private final IntegrationEvents integrationEvents;

  ReserveStockHandler(StockItems stock, IntegrationEvents integrationEvents) {
    this.stock = stock;
    this.integrationEvents = integrationEvents;
  }

  @Override
  public Boolean handle(ReserveStock command, CommandContext context) {
    Sku sku = new Sku(command.sku());
    StockItem item =
        stock
            .find(sku)
            .orElseThrow(
                () -> new EntityNotFoundException(InventoryErrorCode.SKU_NOT_STOCKED, "no stock for " + sku));
    if (!item.reserve(command.quantity())) {
      return false;
    }
    stock.save(item);
    integrationEvents.publish(new StockReserved(sku.value(), command.quantity()), context);
    return true;
  }

  /** Inventory's one refusal, kept next to its only use case because there is only one. */
  enum InventoryErrorCode implements ErrorCode {
    SKU_NOT_STOCKED("inventory.sku-not-stocked", ErrorCategory.NOT_FOUND);

    private final String code;
    private final ErrorCategory category;

    InventoryErrorCode(String code, ErrorCategory category) {
      this.code = code;
      this.category = category;
    }

    @Override
    public String code() {
      return code;
    }

    @Override
    public ErrorCategory category() {
      return category;
    }
  }
}
