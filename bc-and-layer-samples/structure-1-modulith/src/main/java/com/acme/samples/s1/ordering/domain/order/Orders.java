package com.acme.samples.s1.ordering.domain.order;

import java.util.Optional;

/** Repository port for the Order aggregate; implemented in infrastructure. */
public interface Orders {
    void save(Order order);
    Optional<Order> byId(String id);
    void updateStatus(String id, OrderStatus status);
}
