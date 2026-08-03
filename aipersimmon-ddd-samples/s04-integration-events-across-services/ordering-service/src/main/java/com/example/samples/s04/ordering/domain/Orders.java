package com.example.samples.s04.ordering.domain;

import com.aipersimmon.ddd.core.annotation.Repository;
import java.util.Optional;

/** The write port. */
@Repository
public interface Orders {

  void save(Order order);

  /**
   * The order with this id, <em>within the caller's tenant</em>.
   *
   * <p>The signature says nothing about tenancy, and that is the point: the tenant is ambient and the
   * SQL is rewritten to carry it. So a foreign tenant's id resolves to {@link Optional#empty()} — the
   * same answer as an id that never existed, which is the only safe one. "Exists, but not yours"
   * would confirm the id to a caller with no business knowing it.
   */
  Optional<Order> find(OrderId id);
}
