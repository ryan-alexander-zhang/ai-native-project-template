package com.example.samples.s11.ordering.domain;

import com.aipersimmon.ddd.core.annotation.Repository;
import java.util.Optional;

/** The write port. The sweep's candidate scan is not here — reading a list is not a write concern. */
@Repository
public interface Orders {

  void save(Order order);

  Optional<Order> findById(OrderId id);
}
