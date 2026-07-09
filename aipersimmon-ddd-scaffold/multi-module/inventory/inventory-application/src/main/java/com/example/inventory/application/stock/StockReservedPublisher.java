package com.example.inventory.application.stock;

import com.example.inventory.api.StockReserved;

/**
 * Port for announcing the {@link StockReserved} integration event once a
 * reservation succeeds. The infrastructure layer supplies the transport.
 */
public interface StockReservedPublisher {

    void publish(StockReserved event);
}
