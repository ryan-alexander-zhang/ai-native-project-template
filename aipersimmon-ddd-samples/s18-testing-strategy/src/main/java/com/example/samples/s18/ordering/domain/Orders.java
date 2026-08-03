package com.example.samples.s18.ordering.domain;

import com.aipersimmon.ddd.core.annotation.Repository;
import java.util.Optional;

/** The port. The integration test exercises the adapter; the application tests replace it in memory. */
@Repository
public interface Orders {

  Optional<Order> findById(OrderId id);

  void save(Order order);
}
