package com.example.ordering.application.order;

import java.util.List;

/**
 * The ordering context's anti-corruption <em>port</em> for asking the inventory context whether the
 * SKUs on an order can currently be offered. It is expressed in ordering's own language and types:
 * the application depends only on this interface, never on the inventory context's published
 * contract. The infrastructure layer's gateway adapter implements it — translating to and from
 * inventory's contract and hiding whether the call is in-process or remote — so this port is the
 * whole ordering context's view of inventory.
 *
 * <p>This is a synchronous <em>query</em> used to fail fast at order time: it lets the place-order
 * use case reject an order for a SKU inventory does not carry before it creates anything. It
 * reserves nothing — the authoritative, atomic reservation happens asynchronously once the order is
 * cleared for fulfilment (the {@code OrderReadyForFulfilment} → reserve-stock → process-manager
 * path), which is where a state change belongs.
 */
public interface StockAvailabilityGateway {

  /**
   * @param lines the order's lines as (sku, quantity) pairs — the quantity is what makes the answer
   *     useful (issue-00150): "is any on hand?" let a 999-unit order through a stock of 5, placed
   *     only to walk the whole compensation circle
   * @return the verdict: whether every line's quantity is offerable, and if not, which SKUs are not
   */
  Availability check(List<Line> lines);

  /** One ordered SKU and how many of it, in ordering's own words. */
  record Line(String sku, int quantity) {}

  /** Ordering's own view of the answer: are all SKUs offerable, and if not, which are not. */
  record Availability(boolean allAvailable, List<String> unavailableSkus) {
    public Availability {
      // Defensive copy so this immutable verdict cannot be mutated through the caller's
      // list reference after construction.
      unavailableSkus = unavailableSkus == null ? null : List.copyOf(unavailableSkus);
    }
  }
}
