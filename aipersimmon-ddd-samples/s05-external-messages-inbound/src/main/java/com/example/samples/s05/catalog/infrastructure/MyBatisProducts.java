package com.example.samples.s05.catalog.infrastructure;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.persistence.mybatisplus.MybatisPlusAggregateRepository;
import com.example.samples.s05.catalog.domain.Product;
import com.example.samples.s05.catalog.domain.Products;
import com.example.samples.s05.catalog.domain.Sku;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** The write path. Mapping detail is S17. */
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
        Product.reconstitute(
            new Sku(row.getSku()),
            row.getName(),
            row.getPriceCents(),
            row.getUpstreamRevision(),
            row.getVersion()));
  }

  @Override
  protected ProductRow toRow(Product product) {
    ProductRow row = new ProductRow();
    row.setSku(product.id().value());
    row.setName(product.name());
    row.setPriceCents(product.priceCents());
    row.setUpstreamRevision(product.upstreamRevision());
    return row;
  }
}
