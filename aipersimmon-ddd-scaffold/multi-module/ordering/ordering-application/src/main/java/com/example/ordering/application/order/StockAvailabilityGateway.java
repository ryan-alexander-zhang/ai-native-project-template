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
 * reserves nothing — the authoritative, atomic reservation happens asynchronously after the order
 * is placed (the {@code OrderPlaced} → reserve-stock → saga path), which is where a state change
 * belongs.
 */
public interface StockAvailabilityGateway {

  /**
   * @param skus the SKUs to check (typically the distinct SKUs of an order's lines)
   * @return the verdict: whether every SKU is offerable, and if not, which are not
   */
  Availability check(List<String> skus);

  /** Ordering's own view of the answer: are all SKUs offerable, and if not, which are not. */
  record Availability(boolean allAvailable, List<String> unavailableSkus) {
    public Availability {
      // Defensive copy so this immutable verdict cannot be mutated through the caller's
      // list reference after construction.
      unavailableSkus = unavailableSkus == null ? null : List.copyOf(unavailableSkus);
    }
  }
}
