package com.acme.samples.s3.ordering.app;

/** Driven port: synchronous cross-service availability check against inventory-service (HTTP). */
public interface InventoryPort {
    boolean isAvailable(String sku, int qty);
}
