package com.example.samples.s03.ordering.domain;

import com.aipersimmon.ddd.core.annotation.Repository;
import java.util.Optional;

/** The port. Its {@code save} is the single place domain events are drained and published. */
@Repository
public interface Orders {

  Optional<Order> findById(OrderId id);

  void save(Order order);
}
