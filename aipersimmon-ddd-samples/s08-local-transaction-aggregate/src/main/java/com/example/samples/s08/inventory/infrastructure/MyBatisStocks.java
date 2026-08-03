package com.example.samples.s08.inventory.infrastructure;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.persistence.mybatisplus.MybatisPlusAggregateRepository;
import com.example.samples.s08.inventory.domain.Sku;
import com.example.samples.s08.inventory.domain.Stock;
import com.example.samples.s08.inventory.domain.Stocks;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** The stock adapter. */
@Repository
class MyBatisStocks extends MybatisPlusAggregateRepository<Stock, StockRow> implements Stocks {

  private final StockMapper mapper;

  MyBatisStocks(StockMapper mapper, DomainEvents domainEvents) {
    super(mapper, domainEvents);
    this.mapper = mapper;
  }

  @Override
  public void save(Stock stock) {
    saveAggregate(stock);
  }

  @Override
  public Optional<Stock> findBySku(Sku sku) {
    StockRow row = mapper.selectById(sku.value());
    if (row == null) {
      return Optional.empty();
    }
    return Optional.of(
        Stock.reconstitute(new Sku(row.getSku()), row.getAvailable(), row.getVersion()));
  }

  @Override
  protected StockRow toRow(Stock stock) {
    StockRow row = new StockRow();
    row.setSku(stock.id().value());
    row.setAvailable(stock.available());
    return row;
  }
}
