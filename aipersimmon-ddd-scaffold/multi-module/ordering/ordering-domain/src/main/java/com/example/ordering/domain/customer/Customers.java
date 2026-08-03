package com.example.ordering.domain.customer;

import com.aipersimmon.ddd.core.annotation.Repository;
import java.util.Optional;

/** Repository port for the Customer aggregate; implemented in the infrastructure layer. */
@Repository
public interface Customers {

  Optional<Customer> findById(CustomerId id);

  /**
   * Persist a customer whose committed credit has changed.
   *
   * <p>This port was read-only until credit became enforceable, and that absence was the mechanism
   * that made the old limit unenforceable: with nothing ever written there was no contention point,
   * so no number of concurrent placements could ever conflict and the limit could be exceeded
   * arbitrarily. A save with a version check is what turns the rule from a comparison into a
   * constraint.
   */
  void save(Customer customer);
}
