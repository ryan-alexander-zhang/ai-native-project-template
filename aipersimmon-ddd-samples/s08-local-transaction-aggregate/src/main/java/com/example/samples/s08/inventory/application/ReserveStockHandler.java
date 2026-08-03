package com.example.samples.s08.inventory.application;

import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s08.inventory.domain.InventoryErrorCode;
import com.example.samples.s08.inventory.domain.Sku;
import com.example.samples.s08.inventory.domain.Stock;
import com.example.samples.s08.inventory.domain.Stocks;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Writes more than one aggregate in one transaction, deliberately.
 *
 * <p>"One transaction, one aggregate" is the baseline, and this breaks it on purpose because the
 * business asked for all-or-nothing: a reservation that half succeeds leaves an order that can never be
 * fulfilled and stock nobody will release. The alternatives were to remodel the boundary (a single
 * "reservation" aggregate holding every sku — a hot row every reservation contends on) or to reserve
 * each sku in its own transaction and compensate the ones that succeeded (S9's machinery for a rule
 * that does not need it). Breaking the baseline, knowingly and in one place, is the cheapest of the
 * three here.
 *
 * <p>What makes it safe is that the decision happens entirely in memory, before anything is written:
 * every sku is loaded, every rule checked, and only then does the first {@code save} run. There is no
 * point at which some of the reservation is committed and the rest is still being decided.
 *
 * <p>No {@code @Transactional} anywhere: the bus's interceptor opened the transaction before this ran.
 */
@Component
class ReserveStockHandler implements CommandHandler<ReserveStock, Void> {

  private final Stocks stocks;

  ReserveStockHandler(Stocks stocks) {
    this.stocks = stocks;
  }

  @Override
  public Void handle(ReserveStock command, CommandContext context) {
    Map<Sku, Integer> wanted = merge(command.lines());

    // Load and decide, all of it, before writing any of it.
    List<Stock> decided = new ArrayList<>();
    for (Map.Entry<Sku, Integer> line : wanted.entrySet()) {
      Stock stock =
          stocks
              .findBySku(line.getKey())
              .orElseThrow(
                  () ->
                      new EntityNotFoundException(
                          InventoryErrorCode.SKU_NOT_STOCKED,
                          "sku " + line.getKey().value() + " is not stocked"));
      stock.reserve(line.getValue());
      decided.add(stock);
    }

    decided.forEach(stocks::save);
    return null;
  }

  /** Two lines naming one sku are one reservation; otherwise the second load would see stale state. */
  private static Map<Sku, Integer> merge(List<ReserveStock.Line> lines) {
    Map<Sku, Integer> merged = new LinkedHashMap<>();
    for (ReserveStock.Line line : lines) {
      merged.merge(new Sku(line.sku()), line.quantity(), Integer::sum);
    }
    return merged;
  }
}
