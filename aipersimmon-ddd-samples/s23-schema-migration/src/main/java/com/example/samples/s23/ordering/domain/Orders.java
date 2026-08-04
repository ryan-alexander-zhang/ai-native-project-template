package com.example.samples.s23.ordering.domain;

import com.aipersimmon.ddd.core.annotation.Repository;
import java.util.List;
import java.util.Optional;

/** The write port. */
@Repository
public interface Orders {

  void save(Order order);

  Optional<Order> find(OrderId id);

  /**
   * Ids of orders whose handling has never been decided, oldest first, at most {@code limit}.
   *
   * <p>A port method that exists only for a backfill, which is a thing to be deliberate about. It earns its
   * place by being <em>bounded</em>: the backfill takes a page at a time, so it can be interrupted, resumed
   * and rate-limited, and it never holds a transaction open across a table scan. A backfill that loads
   * everything works until the table is large enough to matter, which is exactly when it is run.
   *
   * <p>It also disappears. When the last row is decided this method returns empty forever and can be
   * deleted with the migration that made the column NOT NULL — which is the test of whether a backfill was
   * modelled as a one-off or accidentally became a feature.
   */
  List<OrderId> undecidedHandling(int limit);
}
