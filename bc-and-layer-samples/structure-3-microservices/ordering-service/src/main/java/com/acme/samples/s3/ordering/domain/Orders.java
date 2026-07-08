package com.acme.samples.s3.ordering.domain;

import java.util.Optional;

public interface Orders {
    void save(Order order);
    Optional<Order> byId(String id);
    void updateStatus(String id, OrderStatus status);
}
