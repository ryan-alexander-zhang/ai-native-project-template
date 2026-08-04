package com.example.samples.s22.inventory;

import com.example.samples.s22.inventory.domain.Sku;
import com.example.samples.s22.inventory.domain.StockItem;
import com.example.samples.s22.inventory.domain.StockItems;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.TransientDataAccessResourceException;

/**
 * A switch that makes the database look unavailable, and it lives in the test tree because the thing it
 * stands in for cannot be arranged honestly any other way.
 *
 * <p>The claim under test is what happens to a record whose handler fails for an <em>environmental</em>
 * reason. Producing a real one means taking the container down mid-consume and putting it back, which is
 * a slow test that fails for reasons unrelated to the claim; and stopping the container would also take
 * out the assertions, which read the same database. A repository that throws a {@code DataAccessException}
 * is what the environment failing looks like from where the decision is made — the error handler
 * classifies by exception type, and this delivers exactly the type the real outage would.
 *
 * <p>It decorates rather than replaces the real repository, so nothing about the reservation logic is
 * stubbed out: while the switch is off, the delegate does the work.
 */
@TestConfiguration(proxyBeanMethods = false)
class OutageSimulation {

  /** Whether the database is "down". Static, so a test can flip it without a bean lookup. */
  static final AtomicBoolean DOWN = new AtomicBoolean();

  @Bean
  @Primary
  StockItems flakyStockItems(@Qualifier("myBatisStockItems") StockItems delegate) {
    return new StockItems() {

      @Override
      public void save(StockItem item) {
        refuseIfDown();
        delegate.save(item);
      }

      @Override
      public Optional<StockItem> findBySku(Sku sku) {
        refuseIfDown();
        return delegate.findBySku(sku);
      }

      private void refuseIfDown() {
        if (DOWN.get()) {
          throw new TransientDataAccessResourceException(
              "simulated outage: the connection pool has nothing to give");
        }
      }
    };
  }
}
