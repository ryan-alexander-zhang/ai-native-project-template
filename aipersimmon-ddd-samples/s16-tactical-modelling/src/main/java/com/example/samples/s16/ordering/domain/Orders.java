package com.example.samples.s16.ordering.domain;

import com.aipersimmon.ddd.core.annotation.Repository;
import java.util.Optional;

/**
 * The repository port, declared by the domain in the domain's own words. There is no implementation
 * in this sample — how an aggregate becomes rows is S17. The port belongs here regardless: the domain
 * states what it needs, and an adapter elsewhere satisfies it.
 */
@Repository
public interface Orders {

  Optional<Order> findById(OrderId id);

  void save(Order order);
}
