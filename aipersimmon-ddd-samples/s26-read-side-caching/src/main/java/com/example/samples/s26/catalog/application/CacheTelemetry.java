package com.example.samples.s26.catalog.application;

import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * What the cache did, counted.
 *
 * <p>Plain counters rather than Micrometer meters, because observability wiring is S15's subject and
 * this sample should not re-teach it. In a real service every one of these is a meter with the query
 * type as a tag; the reason to name them here is that <strong>the three numbers below are what tell an
 * operator whether the cache is helping, and the fourth is what tells them it is lying.</strong>
 *
 * <p>Hit ratio alone is the metric that reads well and decides nothing: a cache with a 99% hit ratio on
 * a value nobody was waiting for has bought nothing, and one with a 40% hit ratio on the slowest query
 * in the service may have saved it. What is actionable is hits against {@link #getDatabaseReads()} —
 * how much work was actually avoided — and {@link #getCoalesced()}, which is how much of a stampede
 * single flight absorbed. {@code divergences} is the one that must have an alert on it, because a cache
 * that has quietly stopped being invalidated shows up in none of the others: hit ratio goes <em>up</em>.
 */
@Component
public class CacheTelemetry {

  private final AtomicLong hits = new AtomicLong();
  private final AtomicLong misses = new AtomicLong();
  private final AtomicLong coalesced = new AtomicLong();
  private final AtomicLong evictions = new AtomicLong();
  private final AtomicLong databaseReads = new AtomicLong();
  private final AtomicLong divergences = new AtomicLong();
  private final AtomicLong writeFailures = new AtomicLong();

  public void hit() {
    hits.incrementAndGet();
  }

  public void miss() {
    misses.incrementAndGet();
  }

  /** A caller that waited for another thread's fill instead of running its own. */
  public void coalesced() {
    coalesced.incrementAndGet();
  }

  public void evicted() {
    evictions.incrementAndGet();
  }

  /** A trip to the database that the cache did not prevent. Recorded by the read adapter. */
  public void databaseRead() {
    databaseReads.incrementAndGet();
  }

  /** A cached value that did not match the source when somebody checked. */
  public void diverged() {
    divergences.incrementAndGet();
  }

  /** The cache refused a write. The read still succeeded; nothing was cached. */
  public void writeFailed() {
    writeFailures.incrementAndGet();
  }

  public long getHits() {
    return hits.get();
  }

  public long getMisses() {
    return misses.get();
  }

  public long getCoalesced() {
    return coalesced.get();
  }

  public long getEvictions() {
    return evictions.get();
  }

  public long getDatabaseReads() {
    return databaseReads.get();
  }

  public long getDivergences() {
    return divergences.get();
  }

  public long getWriteFailures() {
    return writeFailures.get();
  }

  /** Zeroes every counter. For tests and for an operator who wants a clean window. */
  public void reset() {
    hits.set(0);
    misses.set(0);
    coalesced.set(0);
    evictions.set(0);
    databaseReads.set(0);
    divergences.set(0);
    writeFailures.set(0);
  }
}
