package com.example.samples.s26.catalog.application;

import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s26.catalog.domain.CatalogErrorCode;
import com.example.samples.s26.catalog.domain.Product;
import com.example.samples.s26.catalog.domain.Products;
import com.example.samples.s26.catalog.domain.Sku;
import org.springframework.stereotype.Component;

/** Reprice it. The second write that invalidates the same entry, and it says nothing about caches. */
@Component
class RepriceProductHandler implements CommandHandler<RepriceProduct, Void> {

  private final Products products;

  RepriceProductHandler(Products products) {
    this.products = products;
  }

  @Override
  public Void handle(RepriceProduct command, CommandContext context) {
    Product product =
        products
            .find(new Sku(command.sku()))
            .orElseThrow(
                () ->
                    new EntityNotFoundException(
                        CatalogErrorCode.PRODUCT_NOT_FOUND, "no product " + command.sku()));
    if (!product.repriceTo(command.priceCents())) {
      return null;
    }
    products.save(product);
    return null;
  }
}
