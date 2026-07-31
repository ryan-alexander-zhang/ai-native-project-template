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
  private final OrderRef orderId;
  private final Map<Sku, Integer> heldBySku;
  private boolean released;

  /**
   * Whether this instance's held quantities differ from what is stored.
   *
   * <p>Transient — never persisted, never part of identity. It lets a persistence adapter ask the
   * aggregate a question only the aggregate can answer instead of guessing, which it previously did
   * by rewriting every held line on each save. A release changes only {@link #released}, yet the
   * quantities were deleted and re-inserted to arrive at the rows already there (issue-00090).
   *
   * <p>Only ever true for a newly created reservation, because the held set is fixed at creation.
   * Stated as a flag rather than assumed, so a future partial-release use case sets it and the
   * adapter needs no change.
   */
  private boolean heldSetChanged;

  public Reservation(ReservationId id, OrderRef orderId, Map<Sku, Integer> heldBySku) {
    if (id == null) {
      throw new DomainException("a reservation needs its identity");
    }
    if (orderId == null) {
      throw new DomainException("a reservation must reference an order");
    }
    if (heldBySku == null || heldBySku.isEmpty()) {
      throw new DomainException("a reservation must hold at least one line");
    }
    for (Map.Entry<Sku, Integer> held : heldBySku.entrySet()) {
      // A non-positive hold is not a smaller hold, it is corrupt state: persisted without
      // complaint, it explodes two transactions later when the release hands the quantity to
      // Stock.release in a different aggregate — far from whatever produced it.
      if (held.getValue() == null || held.getValue() <= 0) {
        throw new DomainException(
            "a held quantity must be > 0, was " + held.getValue() + " for " + held.getKey());
      }
    }
    this.id = id;
    this.orderId = orderId;
    this.heldBySku = Map.copyOf(heldBySku);
    this.released = false;
    this.heldSetChanged = true;
  }

  /**
   * Reconstitute a stored reservation, including whether it was already released. For persistence
   * adapters only: it restores the {@code released} flag directly instead of replaying {@link
   * #markReleased()}, so rehydration never runs behaviour.
   *
   * @param version the row's optimistic-lock version, which the repository puts back in the {@code
   *     WHERE} clause when it saves
   */
  public static Reservation reconstitute(
      ReservationId id,
      OrderRef orderId,
      Map<Sku, Integer> heldBySku,
      boolean released,
      long version) {
    Reservation reservation = new Reservation(id, orderId, heldBySku);
    reservation.released = released;
    // These held rows came *from* the database, so there is nothing to write back. The public
    // constructor marks them as new, which is right for a fresh reservation and wrong here.
    reservation.heldSetChanged = false;
    reservation.restoreVersion(version);
    return reservation;
  }

  public OrderRef orderId() {
    return orderId;
  }

  /**
   * Whether the stored held-quantity rows need rewriting — see {@link #heldSetChanged}. Called by
   * the persistence adapter; {@code false} on a reconstituted reservation.
   */
  public boolean heldSetChanged() {
    return heldSetChanged;
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
