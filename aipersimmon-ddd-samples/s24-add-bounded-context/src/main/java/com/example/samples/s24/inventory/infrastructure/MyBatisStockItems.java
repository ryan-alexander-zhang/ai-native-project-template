package com.example.samples.s24.inventory.infrastructure;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.persistence.mybatisplus.MybatisPlusAggregateRepository;
import com.example.samples.s24.inventory.domain.Sku;
import com.example.samples.s24.inventory.domain.StockItem;
import com.example.samples.s24.inventory.domain.StockItems;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** Stock's write path. */
@Repository
class MyBatisStockItems extends MybatisPlusAggregateRepository<StockItem, StockItemRow>
    implements StockItems {

  private final StockItemMapper mapper;

  MyBatisStockItems(StockItemMapper mapper, DomainEvents domainEvents) {
    super(mapper, domainEvents);
    this.mapper = mapper;
  }

  @Override
  public void save(StockItem item) {
    saveAggregate(item);
  }

  @Override
  public Optional<StockItem> find(Sku sku) {
    StockItemRow row = mapper.selectById(sku.value());
    if (row == null) {
      return Optional.empty();
    }
    return Optional.of(
        StockItem.reconstitute(sku, row.getOnHand(), row.getReserved(), row.getVersion()));
  }

  @Override
  protected StockItemRow toRow(StockItem item) {
    StockItemRow row = new StockItemRow();
    row.setSku(item.id().value());
    row.setOnHand(item.onHand());
    row.setReserved(item.reserved());
    return row;
  }
}
