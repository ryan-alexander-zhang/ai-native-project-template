package com.example.ordering;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Boots the ordering service. The outbox writer and relay, the Kafka dispatcher,
 * the inbox-guarded consumer bridge, and the in-process domain-event publisher all
 * auto-configure; component scanning covers the ordering context's layers.
 */
@SpringBootApplication
public class OrderingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderingServiceApplication.class, args);
    }
}
