package com.example.inventory.application.stock;

import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.core.exception.DomainException;
import com.example.inventory.api.StockReserved;
import com.example.inventory.domain.stock.Sku;
import com.example.inventory.domain.stock.Stock;
import com.example.inventory.domain.stock.Stocks;
import org.springframework.stereotype.Service;

/**
 * Reserves stock for each line of an order, then announces the reservation. This
 * is the inventory context's reaction to an order being placed elsewhere.
 */
@Service
public class ReserveStockService {

    private final Stocks stocks;
    private final IntegrationEvents integrationEvents;

    public ReserveStockService(Stocks stocks, IntegrationEvents integrationEvents) {
        this.stocks = stocks;
        this.integrationEvents = integrationEvents;
    }

    public void reserve(ReserveStockCommand command) {
        for (ReserveStockCommand.Line line : command.lines()) {
            Sku sku = new Sku(line.sku());
            Stock stock = stocks.findBySku(sku)
                    .orElseThrow(() -> new DomainException("unknown sku: " + line.sku()));
            stock.reserve(line.quantity());
            stocks.save(stock);
        }
        integrationEvents.publish(new StockReserved(command.orderId()));
    }
}
