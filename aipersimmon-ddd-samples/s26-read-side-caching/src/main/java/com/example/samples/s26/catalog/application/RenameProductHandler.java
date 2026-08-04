package com.example.samples.s26.catalog.application;

import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s26.catalog.domain.CatalogErrorCode;
import com.example.samples.s26.catalog.domain.Product;
import com.example.samples.s26.catalog.domain.Products;
import com.example.samples.s26.catalog.domain.Sku;
import org.springframework.stereotype.Component;

/**
 * Rename it. No cache code here, and that is the design.
 *
 * <p>The eviction is a consequence of the domain event, not a line in this handler. If it were a line in
 * this handler it would be a line in every handler that touches a product, and the day a second read
 * model appears, every one of them has to be found and edited. Instead the aggregate says <em>what
 * changed</em> and {@link ProductCacheInvalidation} decides what that invalidates.
 */
@Component
class RenameProductHandler implements CommandHandler<RenameProduct, Void> {

  private final Products products;

  RenameProductHandler(Products products) {
    this.products = products;
  }

  @Override
  public Void handle(RenameProduct command, CommandContext context) {
    Product product = load(command.sku());
    if (!product.renameTo(command.name())) {
      return null;
    }
    products.save(product);
    return null;
  }

  private Product load(String sku) {
    return products
        .find(new Sku(sku))
        .orElseThrow(
            () ->
                new EntityNotFoundException(
                    CatalogErrorCode.PRODUCT_NOT_FOUND, "no product " + sku));
  }
}
