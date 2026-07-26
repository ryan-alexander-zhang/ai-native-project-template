package com.example.inventory.infrastructure.persistence;

import com.aipersimmon.ddd.application.DomainEvents;
import com.example.inventory.domain.stock.Sku;
import com.example.inventory.domain.stock.Stock;
import com.example.inventory.domain.stock.Stocks;
import java.util.Optional;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

/** PostgreSQL-backed {@link Stocks} over {@code inventory.stocks}. */
@Repository
public class MyBatisStocks implements Stocks {

  private final StockMapper stocks;
  private final DomainEvents domainEvents;

  public MyBatisStocks(StockMapper stocks, DomainEvents domainEvents) {
    this.stocks = stocks;
    this.domainEvents = domainEvents;
  }

  /**
   * Write the row under its optimistic-lock version, then drain the aggregate's events. The version
   * check is what makes overselling impossible: two concurrent reservations of one SKU both pass
   * {@code Stock.reserve} on the snapshot they loaded, but only the first update matches a row —
   * the second is refused instead of silently storing the same decremented quantity (issue-00051).
   */
  @Override
  public void save(Stock stock) {
    StockDo row = new StockDo();
    row.setSku(stock.id().value());
    row.setAvailable(stock.available());
    if (stock.version() == 0) {
      row.setVersion(1L);
      stocks.insert(row);
    } else {
      row.setVersion(stock.version());
      if (stocks.updateById(row) == 0) {
        throw new OptimisticLockingFailureException(
            "stock " + stock.id().value() + " was modified concurrently");
      }
    }
    stock.versionAdvanced();
    domainEvents.publishAndClear(stock);
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
