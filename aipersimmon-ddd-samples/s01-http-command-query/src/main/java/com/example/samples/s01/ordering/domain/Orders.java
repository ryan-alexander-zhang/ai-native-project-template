package com.example.samples.s01.ordering.domain;

import com.aipersimmon.ddd.core.annotation.Repository;
import java.util.Optional;

/**
 * The order aggregate's repository port, declared by the domain and named in the domain's own terms.
 * There is no generic {@code AggregateRepository} in the library on purpose: it would push
 * {@code findAll}/{@code update}-shaped vocabulary into the domain language.
 */
@Repository
public interface Orders {

  Optional<Order> findById(OrderId id);

  void save(Order order);
}
