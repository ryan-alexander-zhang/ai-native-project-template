package com.example.samples.s12.ordering.domain;

import java.util.Optional;

/** The order aggregate's port. Writes only; the read side has its own ports. */
public interface Orders {

  Optional<Order> find(OrderId id);

  void save(Order order);
}
