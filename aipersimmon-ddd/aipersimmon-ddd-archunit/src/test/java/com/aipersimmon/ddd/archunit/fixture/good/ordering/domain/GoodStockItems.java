package com.aipersimmon.ddd.archunit.fixture.good.ordering.domain;

import com.aipersimmon.ddd.core.annotation.Repository;
import java.util.Optional;

/**
 * A well-formed repository port: the core {@code @Repository} on an interface in the domain layer.
 * Exercises the good path of {@code portsShouldBeInterfacesInDomain}; its implementation lives in
 * the infrastructure layer (see {@code GoodInMemoryStockItems}).
 */
@Repository
public interface GoodStockItems {

  Optional<GoodStockItem> findBySku(String sku);

  void save(GoodStockItem item);
}
