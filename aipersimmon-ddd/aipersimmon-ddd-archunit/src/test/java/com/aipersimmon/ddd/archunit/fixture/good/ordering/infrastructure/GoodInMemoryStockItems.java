package com.aipersimmon.ddd.archunit.fixture.good.ordering.infrastructure;

import com.aipersimmon.ddd.archunit.fixture.good.ordering.domain.GoodStockItem;
import com.aipersimmon.ddd.archunit.fixture.good.ordering.domain.GoodStockItems;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/**
 * A well-formed repository implementation: a concrete adapter for the {@link GoodStockItems} port,
 * placed in the infrastructure layer and carrying Spring's {@code @Repository} stereotype.
 * Exercises the good path of both {@code implementationsShouldResideInInfrastructure} and {@code
 * implementationsShouldBeSpringRepositories}.
 */
@Repository
public class GoodInMemoryStockItems implements GoodStockItems {

  private final Map<String, GoodStockItem> bySku = new ConcurrentHashMap<>();

  @Override
  public Optional<GoodStockItem> findBySku(String sku) {
    return Optional.ofNullable(bySku.get(sku));
  }

  @Override
  public void save(GoodStockItem item) {
    bySku.put(item.id(), item);
  }
}
