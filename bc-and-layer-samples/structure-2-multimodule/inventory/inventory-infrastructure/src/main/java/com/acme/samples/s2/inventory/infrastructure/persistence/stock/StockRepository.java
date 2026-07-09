package com.acme.samples.s2.inventory.infrastructure.persistence.stock;

import com.acme.samples.s2.inventory.domain.stock.StockItem;
import com.acme.samples.s2.inventory.domain.stock.StockItems;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class StockRepository implements StockItems {

    private final StockMapper stockMapper;

    public StockRepository(StockMapper stockMapper) { this.stockMapper = stockMapper; }

    @Override
    public Optional<StockItem> bySku(String sku) {
        StockItemPo po = stockMapper.selectById(sku);
        if (po == null) return Optional.empty();
        return Optional.of(new StockItem(po.getSku(), po.getAvailable()));
    }

    @Override
    public void decrement(String sku, int qty) {
        StockItemPo po = stockMapper.selectById(sku);
        if (po == null) throw new IllegalStateException("unknown sku: " + sku);
        po.setAvailable(po.getAvailable() - qty);
        stockMapper.updateById(po);
    }

    @Override
    public void increment(String sku, int qty) {
        StockItemPo po = stockMapper.selectById(sku);
        if (po == null) throw new IllegalStateException("unknown sku: " + sku);
        po.setAvailable(po.getAvailable() + qty);
        stockMapper.updateById(po);
    }
}
