package com.example.howto;

import com.aipersimmon.ddd.application.DomainEvents;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The entry point: places an order (status PENDING) and announces it as an
 * in-process domain event, which starts the fulfilment saga. From there the saga
 * drives the flow by sending commands — this service does not.
 */
@Service
public class OrderService {

    private final JdbcTemplate jdbc;
    private final DomainEvents domainEvents;

    public OrderService(JdbcTemplate jdbc, DomainEvents domainEvents) {
        this.jdbc = jdbc;
        this.domainEvents = domainEvents;
    }

    @Transactional
    public void placeOrder(String orderId, String sku) {
        jdbc.update("INSERT INTO orders (id, status) VALUES (?, 'PENDING')", orderId);
        domainEvents.publish(new OrderPlacedEvent(orderId, sku));
    }
}
