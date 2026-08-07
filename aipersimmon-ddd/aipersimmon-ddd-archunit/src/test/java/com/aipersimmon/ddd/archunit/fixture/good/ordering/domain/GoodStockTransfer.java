package com.aipersimmon.ddd.archunit.fixture.good.ordering.domain;

import com.aipersimmon.ddd.core.annotation.AggregateRoot;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;
import java.util.List;

/**
 * A well-formed aggregate that associates with another one: it holds {@link GoodStockItem}'s
 * identity ({@link GoodSku}), not the root itself, so {@code
 * aggregatesShouldReferenceOtherAggregatesByIdentity} passes.
 *
 * <p>The {@code lots} field is the control that keeps the rule from degenerating into "no model
 * types in an aggregate": {@link GoodStockLot} is an {@code @Entity} <em>inside</em> this
 * aggregate, which is exactly what an aggregate is made of, and it must not be reported — including
 * through the generic argument of the list, where a rule that only looked at raw field types would
 * miss it in both directions.
 */
@AggregateRoot
public class GoodStockTransfer extends AbstractAggregateRoot<GoodSku> {

  private final GoodSku sku;
  private final GoodSku destination;
  private final List<GoodStockLot> lots;

  public GoodStockTransfer(GoodSku sku, GoodSku destination, List<GoodStockLot> lots) {
    this.sku = sku;
    this.destination = destination;
    this.lots = List.copyOf(lots);
  }

  @Override
  public GoodSku id() {
    return sku;
  }

  public GoodSku destination() {
    return destination;
  }

  public List<GoodStockLot> lots() {
    return lots;
  }
}
