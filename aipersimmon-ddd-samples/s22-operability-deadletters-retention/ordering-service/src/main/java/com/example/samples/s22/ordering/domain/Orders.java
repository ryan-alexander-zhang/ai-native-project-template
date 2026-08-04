package com.example.samples.s22.ordering.domain;

import com.aipersimmon.ddd.core.annotation.Repository;
import java.util.Optional;

/** The write port. */
@Repository
public interface Orders {

  void save(Order order);

  Optional<Order> find(OrderId id);
}
