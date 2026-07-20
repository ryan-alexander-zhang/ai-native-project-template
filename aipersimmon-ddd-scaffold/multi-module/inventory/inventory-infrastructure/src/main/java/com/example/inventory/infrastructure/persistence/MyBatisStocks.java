package com.example.inventory.infrastructure.persistence;

import com.example.inventory.domain.stock.Sku;
import com.example.inventory.domain.stock.Stock;
import com.example.inventory.domain.stock.Stocks;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** PostgreSQL-backed {@link Stocks} over {@code inventory.stocks}. */
@Repository
public class MyBatisStocks implements Stocks {

    private final StockMapper stocks;

    public MyBatisStocks(StockMapper stocks) {
        this.stocks = stocks;
    }

    @Override
    public void save(Stock stock) {
        String sku = stock.id().value();
        StockDo row = new StockDo();
        row.setSku(sku);
        row.setAvailable(stock.available());
        if (stocks.selectById(sku) == null) {
            stocks.insert(row);
        } else {
            stocks.updateById(row);
        }
    }

    @Override
    public Optional<Stock> findBySku(Sku sku) {
        StockDo row = stocks.selectById(sku.value());
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(new Stock(new Sku(row.getSku()), row.getAvailable()));
    }
}
