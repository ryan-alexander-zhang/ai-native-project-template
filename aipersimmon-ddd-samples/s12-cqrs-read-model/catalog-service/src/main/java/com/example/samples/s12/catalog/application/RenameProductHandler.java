package com.example.samples.s12.catalog.application;

import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s12.catalog.api.ProductRenamed;
import com.example.samples.s12.catalog.domain.CatalogErrorCode;
import com.example.samples.s12.catalog.domain.Product;
import com.example.samples.s12.catalog.domain.Products;
import com.example.samples.s12.catalog.domain.Sku;
import org.springframework.stereotype.Component;

/**
 * Rename the product and tell the world — in one transaction, which is S8's lesson consumed here.
 *
 * <p>{@code IntegrationEvents.publish} writes an outbox row rather than sending anything, so the new name
 * and the announcement of it commit together or not at all. A rename that committed without its event
 * would leave every consumer's copy permanently wrong with nothing to detect it; an event sent without its
 * rename would rename the product everywhere except here.
 *
 * <p>The {@code if} is not a micro-optimisation. A rename to the same name publishes nothing, so an
 * at-least-once caller retrying the same request does not produce a second broadcast that every consumer
 * has to absorb.
 */
@Component
class RenameProductHandler implements CommandHandler<RenameProduct, Void> {

  private final Products products;
  private final IntegrationEvents integrationEvents;

  RenameProductHandler(Products products, IntegrationEvents integrationEvents) {
    this.products = products;
    this.integrationEvents = integrationEvents;
  }

  @Override
  public Void handle(RenameProduct command, CommandContext context) {
    Sku sku = new Sku(command.sku());
    Product product =
        products
            .find(sku)
            .orElseThrow(
                () ->
                    new EntityNotFoundException(
                        CatalogErrorCode.PRODUCT_NOT_FOUND, "no product " + command.sku()));

    if (!product.renameTo(command.name())) {
      return null;
    }
    products.save(product);
    // The context goes with it: the outbox row carries the causal ids, so the consumer's projection writes
    // stay traceable back to whoever renamed the product.
    integrationEvents.publish(new ProductRenamed(sku.value(), product.name()), context);
    return null;
  }
}
