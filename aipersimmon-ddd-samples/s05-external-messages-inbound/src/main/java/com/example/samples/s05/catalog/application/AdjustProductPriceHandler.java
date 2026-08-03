package com.example.samples.s05.catalog.application;

import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.aipersimmon.ddd.inbox.Inbox;
import com.example.samples.s05.catalog.domain.CatalogErrorCode;
import com.example.samples.s05.catalog.domain.Product;
import com.example.samples.s05.catalog.domain.Products;
import com.example.samples.s05.catalog.domain.Sku;
import org.springframework.stereotype.Component;

/**
 * The one handler in all the samples that calls the {@link Inbox} itself — and the reason is worth
 * stating precisely, because doing it in S4 was a bug.
 *
 * <p><strong>Why here.</strong> Nothing upstream of this command has deduplicated: the transport is a
 * plain {@code @KafkaListener} over an ERP's JSON, not the framework's consumer bridge, so there is no
 * bridge to have done it. And the effect is relative — "reduce by 5%" applied twice is a different price
 * from applied once — so no comparison of content can tell a redelivery from a second genuine
 * adjustment. Order does not save it either: a revision guard answers "is this news", and both
 * deliveries carry the same news.
 *
 * <p><strong>Why inside the handler.</strong> The framework's transaction interceptor is at order 200 and
 * the handler runs inside it, so the dedup record and the price change commit or roll back <em>together</em>.
 * That is the property the {@code Inbox} javadoc asks for, and the reason it cannot be moved to the
 * listener: a record written before the transaction would survive a rollback and suppress the retry of a
 * change that never happened — the failure mode that loses money quietly.
 *
 * <p><strong>What the return value means.</strong> {@code false} is "already applied", which is a
 * successful outcome. The library's contract is that {@code alreadyProcessed} returns {@code false} on
 * the first call — having just recorded the key — and {@code true} on a redelivery. Reading it backwards
 * is what made S4's handler skip every message; S4's {@code InboxSemanticsTest} exists because that
 * hypothesis had to be checked against the library rather than assumed.
 */
@Component
class AdjustProductPriceHandler implements CommandHandler<AdjustProductPrice, Boolean> {

  /**
   * The source half of the dedup key. An id is unique only within the system that minted it, so the
   * pair is {@code (source, id)} — and this constant is what stops an ERP message id from colliding
   * with some other upstream's the day a second integration lands on the same table.
   */
  private static final String ERP = "erp";

  private final Products products;
  private final Inbox inbox;

  AdjustProductPriceHandler(Products products, Inbox inbox) {
    this.products = products;
    this.inbox = inbox;
  }

  @Override
  public Boolean handle(AdjustProductPrice command, CommandContext context) {
    if (inbox.alreadyProcessed(ERP, command.upstreamMessageId())) {
      return false;
    }
    Product product =
        products
            .find(new Sku(command.sku()))
            .orElseThrow(
                () ->
                    new EntityNotFoundException(
                        CatalogErrorCode.PRODUCT_NOT_MIRRORED,
                        "no product mirrored for sku " + command.sku()));
    product.adjustPriceBy(-command.reductionPercent());
    products.save(product);
    return true;
  }
}
