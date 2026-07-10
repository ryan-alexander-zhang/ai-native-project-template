package com.example.ordering.domain.order;

import com.aipersimmon.ddd.core.annotation.Repository;
import java.util.Optional;

/**
 * Repository port for the Order aggregate. The interface belongs to the domain;
 * its implementation lives in the infrastructure layer.
 */
@Repository
public interface Orders {

    void save(Order order);

    Optional<Order> findById(OrderId id);
}
