package com.example.samples.s08;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.samples.s08.inventory.application.ReserveStock;
import com.example.samples.s08.inventory.application.ReserveStockWithinBudget;
import com.example.samples.s08.inventory.domain.BudgetId;
import com.example.samples.s08.inventory.domain.Budgets;
import com.example.samples.s08.inventory.domain.ReservationBudget;
import com.example.samples.s08.inventory.domain.Sku;
import com.example.samples.s08.inventory.domain.Stock;
import com.example.samples.s08.inventory.domain.Stocks;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The limit of the optimistic lock, and the way out of it.
 *
 * <p>A version predicate protects <em>the rows a command read and wrote</em>. A rule that spans rows —
 * "no more than N units reserved across all skus" — is read from rows the command never writes, so two
 * commands touching different skus overlap on nothing and neither version notices the other. Both pass
 * the check, both commit, and the rule is broken with no conflict anywhere.
 *
 * <p>Both tests interleave deterministically: the outer transaction stays open on this thread while a
 * second command commits on another one. That is a real interleaving, not a simulated one — the second
 * transaction has to be on its own thread precisely because a nested dispatch would join the first.
 */
class CrossAggregateRuleTest extends InventoryTestBase {

  private static final int LIMIT = 20;

  @Autowired private Stocks stocks;
  @Autowired private Budgets budgets;
  @Autowired private TransactionTemplate tx;

  private final ExecutorService other = Executors.newSingleThreadExecutor();

  @AfterEach
  void stopExecutor() {
    other.shutdownNow();
  }

  @Test
  void perAggregateVersionsDoNotProtectARuleThatSpansThem() {
    tightenTheLimit();

    // A command that checks a cross-sku total the way a handler naturally would: read every stock row,
    // add up what is already reserved, refuse if this reservation would pass the limit.
    tx.executeWithoutResult(
        status -> {
          Stock skuA = stocks.findBySku(new Sku("SKU-A")).orElseThrow();
          int reservedSoFar = reservedAcrossAllSkus();
          assertThat(reservedSoFar + 15).isLessThanOrEqualTo(LIMIT); // the check passes: 0 + 15 <= 20

          // Meanwhile, on another connection, an identical decision is made about a different sku.
          runOnAnotherThread(
              () -> commandBus.send(new ReserveStock(List.of(new ReserveStock.Line("SKU-B", 15)))));

          skuA.reserve(15);
          // No conflict: this row's version is exactly what it was read at. Nothing the other command
          // wrote overlaps with anything this one read.
          stocks.save(skuA);
        });

    // Both commands believed they were within a limit of 20. The total is 30.
    assertThat(reservedAcrossAllSkus()).isEqualTo(30);
    assertThat(reservedAcrossAllSkus()).isGreaterThan(LIMIT);
  }

  @Test
  void givingTheRuleAnOwnerRowMakesTheVersionProtectItAgain() {
    tightenTheLimit();

    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      Stock skuA = stocks.findBySku(new Sku("SKU-A")).orElseThrow();
                      ReservationBudget budget =
                          budgets.findById(new BudgetId("warehouse-1")).orElseThrow();
                      budget.debit(15); // passes against what this transaction read

                      runOnAnotherThread(
                          () ->
                              commandBus.send(
                                  new ReserveStockWithinBudget(
                                      List.of(new ReserveStockWithinBudget.Line("SKU-B", 15)))));

                      skuA.reserve(15);
                      stocks.save(skuA);
                      // Now the budget row's version has moved under us, and this write finds no row.
                      budgets.save(budget);
                    }))
        .hasMessageContaining("was modified concurrently");

    // The interleaving was refused, so the limit held: only the other command's 15 units landed.
    assertThat(reservedUnits()).isEqualTo(15);
    assertThat(reservedAcrossAllSkus()).isEqualTo(15);
    assertThat(availableOf("SKU-A")).isEqualTo(100);
  }

  private void tightenTheLimit() {
    jdbc.update("UPDATE s08_reservation_budget SET limit_units = ? WHERE id = 'warehouse-1'", LIMIT);
  }

  private int reservedAcrossAllSkus() {
    return jdbc.queryForObject("SELECT SUM(100 - available) FROM s08_stock", Integer.class);
  }

  private void runOnAnotherThread(Runnable work) {
    Future<?> done = other.submit(work);
    try {
      done.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } catch (java.util.concurrent.ExecutionException e) {
      throw new IllegalStateException(e.getCause());
    }
  }
}
