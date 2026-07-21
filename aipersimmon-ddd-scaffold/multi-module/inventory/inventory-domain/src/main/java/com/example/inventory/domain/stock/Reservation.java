package com.example.inventory.domain.stock;

import com.aipersimmon.ddd.core.annotation.AggregateRoot;
import com.aipersimmon.ddd.core.annotation.Identity;
import com.aipersimmon.ddd.core.exception.DomainException;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;
import java.util.List;
import java.util.Map;

/**
 * A record of what was reserved for one order, addressed by a {@link ReservationId}. It exists so a
 * later release can be <em>exact</em> and <em>idempotent</em>: it remembers the quantities held per
 * SKU, and once {@link #markReleased() released} it refuses to release again — so a retried or
 * duplicated {@code ReleaseStock} restores stock at most once. Without it, "release the stock for
 * this order" would be a guess.
 */
@AggregateRoot
public class Reservation extends AbstractAggregateRoot<ReservationId> {

  private final ReservationId id;
  private final String orderId;
  private final Map<Sku, Integer> heldBySku;
  private boolean released;

  public Reservation(ReservationId id, String orderId, Map<Sku, Integer> heldBySku) {
    if (orderId == null || orderId.isBlank()) {
      throw new DomainException("a reservation must reference an order");
    }
    if (heldBySku == null || heldBySku.isEmpty()) {
      throw new DomainException("a reservation must hold at least one line");
    }
    this.id = id;
    this.orderId = orderId;
    this.heldBySku = Map.copyOf(heldBySku);
    this.released = false;
  }

  public String orderId() {
    return orderId;
  }

  public boolean isReleased() {
    return released;
  }

  /** The quantities to hand back on release, as (sku, quantity) pairs. */
  public List<Map.Entry<Sku, Integer>> held() {
    return List.copyOf(heldBySku.entrySet());
  }

  /**
   * Mark this reservation released.
   *
   * @return {@code true} if this call performed the release, {@code false} if it was already
   *     released — the caller uses this to make the stock hand-back happen exactly once.
   */
  public boolean markReleased() {
    if (released) {
      return false;
    }
    released = true;
    return true;
  }

  @Override
  @Identity
  public ReservationId id() {
    return id;
  }
}
