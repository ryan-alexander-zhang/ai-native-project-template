package com.example.samples.s24.inventory.application;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s24.inventory.domain.Sku;
import com.example.samples.s24.inventory.domain.StockItem;
import com.example.samples.s24.inventory.domain.StockItems;
import org.springframework.stereotype.Component;

/** Put stock on the shelf. */
@Component
class StockStockedHandler implements CommandHandler<StockStocked, Void> {

  private final StockItems stock;

  StockStockedHandler(StockItems stock) {
    this.stock = stock;
  }

  @Override
  public Void handle(StockStocked command, CommandContext context) {
    stock.save(StockItem.stocked(new Sku(command.sku()), command.onHand()));
    return null;
  }
}
