package com.example.samples.s17.ordering.domain;

import com.aipersimmon.ddd.core.annotation.Repository;
import java.util.Optional;

/** The port. Note it exposes no {@code findAll} or {@code update}: the domain asks for what it needs. */
@Repository
public interface Orders {

  Optional<Order> findById(OrderId id);

  void save(Order order);
}
