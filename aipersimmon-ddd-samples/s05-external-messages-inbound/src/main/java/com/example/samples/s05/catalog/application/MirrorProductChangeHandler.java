package com.example.samples.s05.catalog.application;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s05.catalog.domain.ChangeOutcome;
import com.example.samples.s05.catalog.domain.Product;
import com.example.samples.s05.catalog.domain.Products;
import com.example.samples.s05.catalog.domain.Sku;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Upsert, because a mirror has no "create": the first message about a product is the only announcement
 * this context will get.
 *
 * <p>No inbox check here, and for a different reason than in S4. There the consumer bridge had already
 * done it; here nobody has, and it still is not needed — the revision comparison inside the aggregate
 * makes a redelivery a no-op by content. Adding a dedup key would be a second mechanism guarding a
 * property the first one already guarantees, and two mechanisms means two things to keep correct.
 *
 * <p>Contrast {@link AdjustProductPriceHandler}, where the effect is relative and the inbox is the only
 * thing standing between a redelivery and a second discount.
 */
@Component
class MirrorProductChangeHandler implements CommandHandler<MirrorProductChange, ChangeOutcome> {

  private final Products products;

  MirrorProductChangeHandler(Products products) {
    this.products = products;
  }

  @Override
  public ChangeOutcome handle(MirrorProductChange command, CommandContext context) {
    Sku sku = new Sku(command.sku());
    Optional<Product> existing = products.find(sku);
    if (existing.isEmpty()) {
      products.save(
          Product.mirrored(sku, command.name(), command.priceCents(), command.revision()));
      return ChangeOutcome.MIRRORED;
    }
    Product product = existing.get();
    ChangeOutcome outcome =
        product.applyUpstreamChange(command.revision(), command.name(), command.priceCents());
    if (outcome == ChangeOutcome.UPDATED) {
      products.save(product);
    }
    // A superseded change saves nothing — no row touched, no version bumped, no write amplification
    // from a topic replay. The read that decided it was superseded is the only cost.
    return outcome;
  }
}
