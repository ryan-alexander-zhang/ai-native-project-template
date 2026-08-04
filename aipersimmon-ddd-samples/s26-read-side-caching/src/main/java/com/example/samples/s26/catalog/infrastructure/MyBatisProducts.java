package com.example.samples.s26.catalog.infrastructure;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.persistence.mybatisplus.MybatisPlusAggregateRepository;
import com.example.samples.s26.catalog.domain.Product;
import com.example.samples.s26.catalog.domain.Products;
import com.example.samples.s26.catalog.domain.Sku;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * The write path — and the class this sample's architecture test forbids from ever touching Redis.
 *
 * <p>Reads here go to the database on every call, deliberately. A memoising {@code find} would be one field
 * and three lines, would make every test in this sample pass, and would break the write path in the two
 * ways {@code AggregateCacheTrapTest} measures. The absence is the feature.
 */
@Repository
class MyBatisProducts extends MybatisPlusAggregateRepository<Product, ProductRow>
    implements Products {

  private final ProductMapper mapper;

  MyBatisProducts(ProductMapper mapper, DomainEvents domainEvents) {
    super(mapper, domainEvents);
    this.mapper = mapper;
  }

  @Override
  public void save(Product product) {
    saveAggregate(product);
  }

  @Override
  public Optional<Product> find(Sku sku) {
    ProductRow row = mapper.selectById(sku.value());
    if (row == null) {
      return Optional.empty();
    }
    return Optional.of(
        Product.reconstitute(sku, row.getName(), row.getPriceCents(), row.getVersion()));
  }

  @Override
  protected ProductRow toRow(Product product) {
    ProductRow row = new ProductRow();
    row.setSku(product.id().value());
    row.setName(product.name());
    row.setPriceCents(product.priceCents());
    return row;
  }
}
