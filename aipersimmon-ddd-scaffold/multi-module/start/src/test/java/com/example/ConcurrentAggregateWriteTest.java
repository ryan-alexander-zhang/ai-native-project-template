package com.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.tenancy.TenantContext;
import com.aipersimmon.ddd.tenancy.TenantId;
import com.aipersimmon.ddd.tenancy.Tenants;
import com.example.inventory.domain.stock.Sku;
import com.example.inventory.domain.stock.Stock;
import com.example.inventory.domain.stock.Stocks;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The aggregate is a transactional consistency unit: a write based on a stale snapshot must be
 * refused, not silently applied over a concurrent change.
 *
 * <p>Regression guard for issue-00051. Before the optimistic-lock version existed, {@code Stock}
 * oversold under concurrency: two reservations of one SKU each loaded {@code available = 10}, each
 * passed {@code Stock.reserve(8)} against the snapshot they held, and each stored {@code available
 * = 2}. Both "succeeded", 16 units were committed against 10 in stock, and because the stored value
 * was identical either way, no reconciliation of {@code stocks.available} alone could reveal it —
 * only summing the reservations against it could.
 *
 * <p>The conservation invariant below is therefore the assertion that matters: {@code available +
 * total reserved} must equal the starting quantity no matter how the writers interleave.
 */
@SpringBootTest(
    properties = {
      "aipersimmon.ddd.process-manager.jdbc.effect-relay.poll-delay=1h",
      "aipersimmon.ddd.process-manager.jdbc.deadline-worker.poll-delay=1h",
      "aipersimmon.ddd.outbox.poll-delay-ms=3600000",
    })
@Import(TestInfrastructure.class)
class ConcurrentAggregateWriteTest {

  private static final TenantId TENANT = Tenants.of("acme");

  @Autowired Stocks stocks;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager txManager;

  private TransactionTemplate tx() {
    return new TransactionTemplate(txManager);
  }

  /**
   * A write from a stale snapshot is rejected. Deterministic — no threads: load the aggregate, let
   * something else move the row on, then save what was loaded. This is the mechanism the concurrent
   * case relies on, pinned down without timing.
   */
  @Test
  void aWriteFromAStaleSnapshotIsRejected() {
    Sku sku = seedStock(10);

    TenantContext.runAs(
        TENANT,
        () -> {
          Stock loaded = stocks.findBySku(sku).orElseThrow();
          long loadedVersion = loaded.version();
          assertTrue(loadedVersion > 0, "a persisted aggregate loads at a non-zero version");

          // Another transaction advances the row: the snapshot just loaded is now stale.
          tx().executeWithoutResult(
                  status -> {
                    Stock other = stocks.findBySku(sku).orElseThrow();
                    other.reserve(1);
                    stocks.save(other);
                  });

          loaded.reserve(5);
          assertThrows(
              OptimisticLockingFailureException.class,
              () -> tx().executeWithoutResult(status -> stocks.save(loaded)),
              "saving a stale snapshot must be refused, not applied over the concurrent change");
          return null;
        });

    // The refused write left nothing behind: only the other transaction's -1 took effect.
    assertEquals(9, availableOf(sku), "the rejected write must not have changed the row");
  }

  /**
   * Two concurrent reservations of the same SKU, aligned so both load before either saves. Exactly
   * one may win, and stock must be conserved.
   */
  @Test
  void concurrentReservationsOfOneSkuCannotOversell() throws Exception {
    Sku sku = seedStock(10);
    int each = 8; // 8 + 8 > 10: if both writers won, the SKU would be oversold by 6
    CyclicBarrier loaded = new CyclicBarrier(2);
    AtomicInteger won = new AtomicInteger();
    AtomicInteger lost = new AtomicInteger();

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> a = pool.submit(() -> reserve(sku, each, loaded, won, lost));
      Future<?> b = pool.submit(() -> reserve(sku, each, loaded, won, lost));
      a.get();
      b.get();
    } finally {
      pool.shutdownNow();
    }

    assertEquals(1, won.get(), "exactly one reservation may win");
    assertEquals(1, lost.get(), "the loser must be refused, not silently overwrite the winner");
    assertEquals(
        10 - each,
        availableOf(sku),
        "stock is conserved: available + reserved must equal the starting quantity");
  }

  /** Load, reserve, save in one transaction, releasing the barrier once loaded. */
  private void reserve(
      Sku sku, int quantity, CyclicBarrier loadedBarrier, AtomicInteger won, AtomicInteger lost) {
    try {
      TenantContext.runAs(
          TENANT,
          () ->
              tx().execute(
                      status -> {
                        Stock stock = stocks.findBySku(sku).orElseThrow();
                        awaitBarrier(loadedBarrier);
                        stock.reserve(quantity);
                        stocks.save(stock);
                        return null;
                      }));
      won.incrementAndGet();
    } catch (OptimisticLockingFailureException conflict) {
      lost.incrementAndGet();
    }
  }

  private static void awaitBarrier(CyclicBarrier barrier) {
    try {
      barrier.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** A SKU of its own per test, so the two tests cannot disturb each other. */
  private Sku seedStock(int available) {
    String value = "SKU-CONC-" + System.nanoTime();
    jdbc.update(
        "INSERT INTO inventory.stocks (sku, available, tenant_id, version) VALUES (?, ?, ?, 1)",
        value,
        available,
        TENANT.value());
    return new Sku(value);
  }

  private int availableOf(Sku sku) {
    Integer available =
        jdbc.queryForObject(
            "SELECT available FROM inventory.stocks WHERE sku = ? AND tenant_id = ?",
            Integer.class,
            sku.value(),
            TENANT.value());
    assertNotNull(available);
    return available;
  }
}
