package com.example.samples.s26.catalog.application;

import com.example.samples.s26.catalog.domain.Sku;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * The projection: the same number the cache holds, maintained at write time in a table.
 *
 * <p>Put this interface next to {@link QueryCache} and the decision between the two stops being a matter
 * of taste. Three operations here have no counterpart there, and none of them can be added to a cache:
 *
 * <ul>
 *   <li>{@link #top(int)} — a cache is a keyed lookup, so "the ten best sellers" is not a question you
 *       can ask it. You would have to know the answer to construct the key.
 *   <li>{@link #rebuild(Duration)} — a projection can be recomputed from the source and is therefore
 *       disposable in a way that <em>restores</em> it. Flushing a cache also makes it disposable, but
 *       what follows is a cold read path, not a correct table.
 *   <li>{@link #soldRecently(Sku)} — one row read, always, with no miss path. There is no "sometimes
 *       this costs a scan", so there is no tail latency to explain to anyone.
 * </ul>
 *
 * <p>What it costs, in exchange: a second write on the sale path ({@link #add}), a table that can drift
 * from its source and needs the rebuild to exist, and a schema change every time the read shape changes.
 * The cache costs none of those and cannot do any of the three above. That is the trade, and it is not a
 * hit-ratio argument.
 */
public interface SalesBoard {

  /** Add to a product's running figure, inserting the row if this is its first sale. */
  void add(Sku sku, int quantity);

  /**
   * Recompute every row from {@link OrderLines} over {@code window}, and return how many rows were
   * written.
   *
   * <p>The operation a cache has no analogue for. It is what makes the projection's correctness
   * recoverable rather than merely hoped for: a missed {@link #add}, a bug in the delta, a restore from
   * an older backup — all of them are repaired by running this, because the projection is derived data
   * and the source it derives from is still there.
   */
  int rebuild(Duration window);

  /** One product's figure, straight from the projection. */
  Optional<Long> soldRecently(Sku sku);

  /** The busiest products first. The query no cache can answer. */
  List<TopSeller> top(int limit);
}
