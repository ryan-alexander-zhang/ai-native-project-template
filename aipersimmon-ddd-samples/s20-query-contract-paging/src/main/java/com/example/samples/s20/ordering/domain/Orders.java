package com.example.samples.s20.ordering.domain;

import com.aipersimmon.ddd.core.annotation.Repository;
import java.util.Optional;

/**
 * The write port, and deliberately only that: two methods, both of which a command needs.
 *
 * <p>No {@code findAll}, no {@code search}. A list query answered by loading aggregates rebuilds
 * state and invariants that rendering a list never uses, and it grows the write port into a
 * general-purpose query API that nothing can then change safely. The read side gets its own port,
 * in the application layer — see {@code OrderQueries}.
 */
@Repository
public interface Orders {

  void save(Order order);

  Optional<Order> findById(OrderId id);
}
