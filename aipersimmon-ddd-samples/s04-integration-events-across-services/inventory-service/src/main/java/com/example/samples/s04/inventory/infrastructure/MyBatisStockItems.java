package com.example.samples.s04.inventory.infrastructure;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.persistence.mybatisplus.MybatisPlusAggregateRepository;
import com.example.samples.s04.inventory.domain.Sku;
import com.example.samples.s04.inventory.domain.StockItem;
import com.example.samples.s04.inventory.domain.StockItems;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** The write path. The inbox row is written by the framework's store, in this same transaction. */
@Repository
class MyBatisStockItems extends MybatisPlusAggregateRepository<StockItem, StockRow>
    implements StockItems {

  private final StockMapper mapper;

  MyBatisStockItems(StockMapper mapper, DomainEvents domainEvents) {
    super(mapper, domainEvents);
    this.mapper = mapper;
  }

  @Override
  public void save(StockItem item) {
    saveAggregate(item);
  }

  @Override
  public Optional<StockItem> findBySku(Sku sku) {
    StockRow row = mapper.selectById(sku.value());
    if (row == null) {
      return Optional.empty();
    }
    return Optional.of(
        StockItem.reconstitute(
            new Sku(row.getSku()), row.getAvailable(), row.getReserved(), row.getVersion()));
  }

  @Override
  protected StockRow toRow(StockItem item) {
    StockRow row = new StockRow();
    row.setSku(item.id().value());
    row.setAvailable(item.available());
    row.setReserved(item.reserved());
    return row;
  }
}
