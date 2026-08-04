package com.example.samples.s26;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.samples.s26.catalog.application.CacheKeys;
import com.example.samples.s26.catalog.application.RebuildSalesBoard;
import com.example.samples.s26.catalog.application.SalesWindow;
import com.example.samples.s26.catalog.application.TopSeller;
import com.example.samples.s26.catalog.domain.Sku;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The two answers to "this read is too slow", side by side, with the differences that decide between them.
 *
 * <p>Not a hit-ratio comparison. Everything asserted here is a capability one of them has and the other cannot
 * be given.
 */
class ProjectionVersusCacheTest extends CacheTestBase {

  @Test
  void onlyTheProjectionCanBeAskedForTheBestSellers() {
    sell(MOUSE, 9);
    sell(KEYBOARD, 4);
    sell(MONITOR, 1);

    List<TopSeller> top = queryBus.ask(new com.example.samples.s26.catalog.application.TopSellersQuery(3));

    assertThat(top).extracting(TopSeller::sku).containsExactly(MOUSE, KEYBOARD, MONITOR);
    assertThat(top).extracting(TopSeller::soldRecently).containsExactly(9L, 4L, 1L);
    // And the cache has nothing to offer here: the answer is a sort over the whole catalogue, so there is no
    // key a caller could have known to ask for. The cache holds three product details and not this list.
    assertThat(cache.get(keyOf(KEYBOARD))).isEmpty();
  }

  /**
   * A flush empties the cache; a rebuild fills the projection. Both are "throw it away and start again", and
   * they do not end in the same place.
   *
   * <p>After the flush the cache holds nothing and the next read is a trip to the source — correct, and slow.
   * After the rebuild the projection holds every row again and can answer the sorted query immediately. That
   * is the difference between derived data with a source to be recomputed from and derived data that is
   * <em>itself</em> the only copy.
   */
  @Test
  void aflushLeavesNothingAndArebuildLeavesATable() {
    sell(KEYBOARD, 4);
    detail(KEYBOARD);
    assertThat(cache.get(keyOf(KEYBOARD))).isPresent();
    telemetry.reset();

    int flushed = cache.evictMatching(CacheKeys.PREFIX + "*");

    assertThat(flushed).isPositive();
    assertThat(cache.get(keyOf(KEYBOARD))).isEmpty();
    // The cache's remedy costs a read for every entry that was dropped.
    detail(KEYBOARD);
    assertThat(telemetry.getDatabaseReads()).isEqualTo(1);

    // The projection's remedy produces rows.
    Integer rows = commandBus.send(new RebuildSalesBoard(SalesWindow.RECENT));

    assertThat(rows).isEqualTo(1);
    assertThat(salesBoard.soldRecently(new Sku(KEYBOARD))).contains(4L);
  }

  /**
   * A projection that has drifted is repairable from its source. That is the property the cache does not have.
   *
   * <p>The drift is written in directly, because that is how it happens: a bug in a delta, a migration that
   * missed a case, a restore from a backup taken at the wrong moment. The rebuild does not need to know what
   * went wrong — it recomputes from {@code s26_order_line}, which is still the truth.
   *
   * <p>The cache's equivalent of this repair is a flush, and the difference is that a flush restores
   * <em>nothing</em>: correctness returns one read at a time, as callers happen to ask. For a value that a
   * page is waiting on that is fine. For the best-sellers list it would mean the list is unavailable until
   * somebody recomputes it, which is to say the cache was never able to hold that list in the first place.
   */
  @Test
  void arebuildRepairsDriftThatNothingElseWouldHaveNoticed() {
    sell(KEYBOARD, 4);
    sell(MOUSE, 9);

    jdbc.update("UPDATE s26_product_sales SET sold_recently = 0");
    assertThat(salesBoard.soldRecently(new Sku(KEYBOARD))).contains(0L);
    assertThat(salesBoard.top(2)).extracting(TopSeller::soldRecently).containsExactly(0L, 0L);

    commandBus.send(new RebuildSalesBoard(SalesWindow.RECENT));

    assertThat(salesBoard.soldRecently(new Sku(KEYBOARD))).contains(4L);
    assertThat(salesBoard.top(2)).extracting(TopSeller::sku).containsExactly(MOUSE, KEYBOARD);
  }

  /**
   * The projection is a table, so it costs a write on the sale path. The cache costs nothing there.
   *
   * <p>Asserted as a row count rather than a timing, because that is the durable form of the cost: every sale
   * of a product touches the same projection row, which under enough concurrency on one popular sku is a
   * contention point that the append-only fact table never was.
   */
  @Test
  void thesaleWritesTwiceBecauseTheProjectionExists() {
    sell(KEYBOARD, 4);

    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM s26_order_line", Long.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM s26_product_sales", Long.class)).isEqualTo(1);
  }
}
