package com.acme.samples.s3.inventory.app.stock;

import com.acme.samples.s3.inventory.domain.stock.StockItems;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only use case backing the synchronous /availability endpoint. */
@Service
public class CheckAvailabilityService {

    private final StockItems stock;

    public CheckAvailabilityService(StockItems stock) { this.stock = stock; }

    @Transactional(readOnly = true)
    public boolean isAvailable(String sku, int qty) {
        return stock.bySku(sku).map(s -> s.canReserve(qty)).orElse(false);
    }
}
