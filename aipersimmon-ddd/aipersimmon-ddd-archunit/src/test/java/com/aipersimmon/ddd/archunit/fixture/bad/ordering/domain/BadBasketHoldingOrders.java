package com.aipersimmon.ddd.archunit.fixture.bad.ordering.domain;

import com.aipersimmon.ddd.archunit.fixture.good.ordering.domain.GoodSku;
import com.aipersimmon.ddd.archunit.fixture.good.ordering.domain.GoodStockItem;
import com.aipersimmon.ddd.core.annotation.AggregateRoot;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;
import java.util.List;

/**
 * Violates {@code aggregatesShouldReferenceOtherAggregatesByIdentity}: an aggregate root holding
 * another root outright, and a collection of them, rather than their identifiers.
 *
 * <p>Both forms are here because they fail differently in production and identically in review: the
 * bare field makes one write contend on two roots, and the list makes the size of the transaction a
 * function of how much data the other aggregate accumulated.
 */
@AggregateRoot
public class BadBasketHoldingOrders extends AbstractAggregateRoot<GoodSku> {

  private final GoodSku id;
  private final GoodStockItem reservedItem;
  private final List<GoodStockItem> relatedItems;

  public BadBasketHoldingOrders(
      GoodSku id, GoodStockItem reservedItem, List<GoodStockItem> relatedItems) {
    this.id = id;
    this.reservedItem = reservedItem;
    this.relatedItems = List.copyOf(relatedItems);
  }

  @Override
  public GoodSku id() {
    return id;
  }

  public GoodStockItem reservedItem() {
    return reservedItem;
  }

  public List<GoodStockItem> relatedItems() {
    return relatedItems;
  }
}
