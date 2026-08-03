package com.example.samples.s02.ordering.domain;

import com.aipersimmon.ddd.core.annotation.Repository;
import java.util.Optional;

/** The write-side port. */
@Repository
public interface Orders {

  Optional<Order> findById(OrderId id);

  void save(Order order);
}
