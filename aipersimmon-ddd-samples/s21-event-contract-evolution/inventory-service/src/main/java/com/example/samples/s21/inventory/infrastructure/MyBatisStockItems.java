package com.example.samples.s21.inventory.infrastructure;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.persistence.mybatisplus.MybatisPlusAggregateRepository;
import com.example.samples.s21.inventory.domain.StockItem;
import com.example.samples.s21.inventory.domain.StockItems;
import com.example.samples.s21.inventory.domain.StockLocation;
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
  public Optional<StockItem> find(StockLocation location) {
    StockRow row = mapper.selectById(location.value());
    if (row == null) {
      return Optional.empty();
    }
    return Optional.of(
        StockItem.reconstitute(
            new StockLocation(row.getSku(), row.getWarehouse()),
            row.getAvailable(),
            row.getReserved(),
            row.getVersion()));
  }

  @Override
  protected StockRow toRow(StockItem item) {
    StockRow row = new StockRow();
    row.setLocation(item.id().value());
    row.setSku(item.id().sku());
    row.setWarehouse(item.id().warehouse());
    row.setAvailable(item.available());
    row.setReserved(item.reserved());
    return row;
  }
}
