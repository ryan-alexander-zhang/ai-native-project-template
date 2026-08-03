package com.example.inventory.infrastructure.persistence;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.persistence.mybatisplus.MybatisPlusAggregateRepository;
import com.example.inventory.domain.stock.Sku;
import com.example.inventory.domain.stock.Stock;
import com.example.inventory.domain.stock.Stocks;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** PostgreSQL-backed {@link Stocks} over {@code inventory.stocks}. */
@Repository
public class MyBatisStocks extends MybatisPlusAggregateRepository<Stock, StockDo>
    implements Stocks {

  private final StockMapper stocks;

  public MyBatisStocks(StockMapper stocks, DomainEvents domainEvents) {
    super(stocks, domainEvents);
    this.stocks = stocks;
  }

  /**
   * The base class writes the row under its optimistic-lock version and drains the aggregate's
   * events. That version check is what makes overselling impossible: two concurrent reservations of
   * one SKU both pass {@code Stock.reserve} on the snapshot they loaded, but only the first update
   * matches a row — the second is refused instead of silently storing the same decremented
   * quantity.
   */
  @Override
  public void save(Stock stock) {
    saveAggregate(stock);
  }

  @Override
  protected StockDo toRow(Stock stock) {
    StockDo row = new StockDo();
    row.setSku(stock.id().value());
    row.setAvailable(stock.available());
    return row;
  }

  @Override
  public Optional<Stock> findBySku(Sku sku) {
    StockDo row = stocks.selectById(sku.value());
    if (row == null) {
      return Optional.empty();
    }
    return Optional.of(
        Stock.reconstitute(new Sku(row.getSku()), row.getAvailable(), row.getVersion()));
  }
}
