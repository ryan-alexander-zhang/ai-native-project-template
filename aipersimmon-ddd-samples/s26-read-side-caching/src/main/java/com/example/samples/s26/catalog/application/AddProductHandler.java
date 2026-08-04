package com.example.samples.s26.catalog.application;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s26.catalog.domain.Product;
import com.example.samples.s26.catalog.domain.Products;
import com.example.samples.s26.catalog.domain.Sku;
import org.springframework.stereotype.Component;

/**
 * A create, with nothing to evict.
 *
 * <p>Worth its own handler for the negative reason: a product that did not exist has no cached detail,
 * because the read path never caches a not-found. If it did — negative caching — this handler would be
 * obliged to evict on create, and forgetting that would mean a new product invisible for a whole TTL.
 * The decision not to cache absence is what makes this the plain case it looks like.
 */
@Component
class AddProductHandler implements CommandHandler<AddProduct, Void> {

  private final Products products;

  AddProductHandler(Products products) {
    this.products = products;
  }

  @Override
  public Void handle(AddProduct command, CommandContext context) {
    products.save(
        Product.of(new Sku(command.sku()), command.name(), command.priceCents()));
    return null;
  }
}
