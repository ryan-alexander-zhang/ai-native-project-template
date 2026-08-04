package com.example.samples.s26.catalog.application;

import java.time.Duration;

/**
 * How far back "recently" reaches, in one place.
 *
 * <p>It is a constant rather than two literals because the expensive read and the projection compute the
 * same figure from the same table, and if they disagreed about the window they would disagree about the
 * answer — permanently, in a way that looks exactly like a stale cache. An operator chasing a divergence
 * would find the cache innocent and the two SQL statements one number apart.
 */
public final class SalesWindow {

  /** The window both read paths use. */
  public static final Duration RECENT = Duration.ofDays(30);

  private SalesWindow() {}
}
