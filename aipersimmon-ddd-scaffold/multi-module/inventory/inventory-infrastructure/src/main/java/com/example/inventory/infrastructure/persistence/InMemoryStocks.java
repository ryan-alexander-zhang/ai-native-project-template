package com.example.inventory.infrastructure.persistence;

import com.example.inventory.domain.stock.Sku;
import com.example.inventory.domain.stock.Stock;
import com.example.inventory.domain.stock.Stocks;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/** In-memory {@link Stocks} implementation, seeded with demo stock levels. */
@Repository
public class InMemoryStocks implements Stocks {

    private final Map<String, Stock> store = new ConcurrentHashMap<>();

    public InMemoryStocks() {
        save(new Stock(new Sku("SKU-1"), 10));
        save(new Stock(new Sku("SKU-2"), 5));
    }

    @Override
    public void save(Stock stock) {
        store.put(stock.id().value(), stock);
    }

    @Override
    public Optional<Stock> findBySku(Sku sku) {
        return Optional.ofNullable(store.get(sku.value()));
    }
}
