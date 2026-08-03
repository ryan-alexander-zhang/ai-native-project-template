package com.example.samples.s08.inventory.application;

import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s08.inventory.domain.BudgetId;
import com.example.samples.s08.inventory.domain.Budgets;
import com.example.samples.s08.inventory.domain.InventoryErrorCode;
import com.example.samples.s08.inventory.domain.ReservationBudget;
import com.example.samples.s08.inventory.domain.Sku;
import com.example.samples.s08.inventory.domain.Stock;
import com.example.samples.s08.inventory.domain.Stocks;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The same reservation, with the cross-sku rule enforced by the aggregate that owns it.
 *
 * <p>The budget row is written on every reservation, so its version predicate is what serialises two
 * concurrent reservations that touch different skus — something no stock row's version could do.
 */
@Component
class ReserveStockWithinBudgetHandler implements CommandHandler<ReserveStockWithinBudget, Void> {

  public static final BudgetId WAREHOUSE = new BudgetId("warehouse-1");

  private final Stocks stocks;
  private final Budgets budgets;

  ReserveStockWithinBudgetHandler(Stocks stocks, Budgets budgets) {
    this.stocks = stocks;
    this.budgets = budgets;
  }

  @Override
  public Void handle(ReserveStockWithinBudget command, CommandContext context) {
    Map<Sku, Integer> wanted = new LinkedHashMap<>();
    for (ReserveStockWithinBudget.Line line : command.lines()) {
      wanted.merge(new Sku(line.sku()), line.quantity(), Integer::sum);
    }

    List<Stock> decided = new ArrayList<>();
    int units = 0;
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
      units += line.getValue();
    }

    ReservationBudget budget =
        budgets
            .findById(WAREHOUSE)
            .orElseThrow(
                () ->
                    new EntityNotFoundException(
                        InventoryErrorCode.SKU_NOT_STOCKED, "no budget for the warehouse"));
    budget.debit(units);

    decided.forEach(stocks::save);
    budgets.save(budget);
    return null;
  }
}
