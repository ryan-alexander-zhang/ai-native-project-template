package com.example.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Boots the inventory service. It has no web endpoint: the inbox-guarded Kafka
 * consumer bridge and the outbox writer/relay auto-configure, and component
 * scanning covers the inventory context's layers.
 */
@SpringBootApplication
public class InventoryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
    }
}
