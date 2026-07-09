package com.acme.samples.s2.ordering.domain.order;

import java.util.Optional;

/** Repository port for the Order aggregate. Part of the aggregate's public API. */
public interface Orders {
    void save(Order order);
    Optional<Order> byId(String id);
    void updateStatus(String id, OrderStatus status);
}
