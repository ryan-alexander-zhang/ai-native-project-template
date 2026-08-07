package com.example.samples.s05.catalog.application;

import com.aipersimmon.ddd.cqrs.QueryHandler;
import com.example.samples.s05.catalog.domain.Products;
import com.example.samples.s05.catalog.domain.Sku;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Projects the mirrored aggregate into the answer. Behind the bus, so the read is subject to the
 * same boundary as every other entry into this application — the controller used to hold {@code
 * Products} and do this itself.
 */
@Component
class FindProductHandler implements QueryHandler<FindProduct, Optional<ProductView>> {

  private final Products products;

  FindProductHandler(Products products) {
    this.products = products;
  }

  @Override
  public Optional<ProductView> handle(FindProduct query) {
    return products
        .find(new Sku(query.sku()))
        .map(
            product ->
                new ProductView(
                    product.id().value(),
                    product.name(),
                    product.priceCents(),
                    product.upstreamRevision()));
  }
}
