package com.example.samples.s12.catalog.infrastructure;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.persistence.mybatisplus.MybatisPlusAggregateRepository;
import com.example.samples.s12.catalog.domain.Product;
import com.example.samples.s12.catalog.domain.Products;
import com.example.samples.s12.catalog.domain.Sku;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** The product adapter. */
@Repository
class MyBatisProducts extends MybatisPlusAggregateRepository<Product, ProductRow>
    implements Products {

  private final ProductMapper mapper;

  MyBatisProducts(ProductMapper mapper, DomainEvents domainEvents) {
    super(mapper, domainEvents);
    this.mapper = mapper;
  }

  @Override
  public Optional<Product> find(Sku sku) {
    ProductRow row = mapper.selectById(sku.value());
    if (row == null) {
      return Optional.empty();
    }
    return Optional.of(Product.reconstitute(sku, row.getName(), row.getVersion()));
  }

  @Override
  public void save(Product product) {
    saveAggregate(product);
  }

  @Override
  protected ProductRow toRow(Product product) {
    ProductRow row = new ProductRow();
    row.setSku(product.id().value());
    row.setName(product.name());
    return row;
  }
}
