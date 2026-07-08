package com.acme.samples.s2.ordering.domain;

import java.util.Optional;

/** Repository port for the Order aggregate. */
public interface Orders {
    void save(Order order);
    Optional<Order> byId(String id);
    void updateStatus(String id, OrderStatus status);
}
