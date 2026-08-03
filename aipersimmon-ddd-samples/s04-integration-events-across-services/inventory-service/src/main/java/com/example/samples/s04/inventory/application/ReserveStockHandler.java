package com.example.samples.s04.inventory.application;

import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s04.inventory.domain.InventoryErrorCode;
import com.example.samples.s04.inventory.domain.Sku;
import com.example.samples.s04.inventory.domain.StockItem;
import com.example.samples.s04.inventory.domain.StockItems;
import org.springframework.stereotype.Component;

/**
 * At-least-once delivery, at-most-once effect.
 *
 * <p><strong>There is no inbox check here, and that is the point.</strong> With the framework's own
 * transport the deduplication has already happened: the consumer bridge consults the {@code Inbox}
 * with {@code (ce_source, ce_id)} and drops a redelivery <em>before</em> publishing the event locally
 * ({@code KafkaIntegrationEventListener:152}). A handler that checks again is not belt and braces —
 * it always finds the bridge's own record and skips the work, silently, for every message. This
 * sample had that bug; the tests caught it as "consumed, inbox row present, stock untouched, no
 * exception".
 *
 * <p>Where the handler <em>does</em> own the check is a transport the bridge is not driving — a
 * foreign system's messages, translated by an adapter of your own (S5). There the {@code Inbox} port
 * is called from inside the command's transaction, so the record and the effect commit or roll back
 * together.
 *
 * <p>The pair matters either way: {@code (source, id)} and never the id alone, because an id is only
 * unique within the producer that minted it. Two producers that happen to share an id would otherwise
 * suppress each other's messages, silently.
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
      Sku sku = new Sku(line.sku());
      StockItem item =
          stockItems
              .findBySku(sku)
              .orElseThrow(
                  () ->
                      new EntityNotFoundException(
                          InventoryErrorCode.SKU_NOT_STOCKED, "sku " + sku.value() + " not stocked"));
      item.reserve(line.quantity());
      stockItems.save(item);
    }
    return null;
  }
}
