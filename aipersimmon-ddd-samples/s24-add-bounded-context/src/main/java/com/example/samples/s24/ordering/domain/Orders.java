package com.example.samples.s24.ordering.domain;

import java.util.Optional;

/** The order repository. */
public interface Orders {

  Optional<Order> find(OrderId id);

  void save(Order order);
}
