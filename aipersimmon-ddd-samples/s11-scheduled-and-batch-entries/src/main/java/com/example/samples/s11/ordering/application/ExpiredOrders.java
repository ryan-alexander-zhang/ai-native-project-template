package com.example.samples.s11.ordering.application;

import java.time.Instant;
import java.util.List;

/**
 * The sweep's candidate scan: which orders <em>looked</em> expired a moment ago.
 *
 * <p>Read carefully — it returns ids, not aggregates, and its answer is <strong>advisory</strong>.
 * Between this call and the command that acts on one of these ids the world moves: the customer pays,
 * an operator closes the order by hand, another instance sweeps the same row. So this is the same
 * shape of thing as a {@code CommandPrecheck} (S19): it narrows the work, it does not decide it. The
 * aggregate decides.
 *
 * <p>It returns ids rather than rows because that is all the caller needs, and because a batch that
 * carries loaded state from the scan into the command is a batch that acts on a stale copy.
 */
public interface ExpiredOrders {

  /**
   * Ids of open orders whose payment deadline has passed, oldest deadline first.
   *
   * @param asOf the moment to judge the deadline against — passed in, never read from a clock here,
   *     so a test can decide what "now" is
   * @param limit hard ceiling on ids returned; a round is bounded so a backlog drains over rounds
   */
  List<String> findExpired(Instant asOf, int limit);
}
