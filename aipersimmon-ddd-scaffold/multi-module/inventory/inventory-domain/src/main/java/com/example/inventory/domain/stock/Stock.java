package com.example.inventory.domain.stock;

import com.aipersimmon.ddd.core.annotation.AggregateRoot;
import com.aipersimmon.ddd.core.annotation.Identity;
import com.aipersimmon.ddd.core.exception.DomainException;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;

/** The Stock aggregate root: available quantity of one SKU, with a reservation rule. */
@AggregateRoot
public class Stock extends AbstractAggregateRoot<Sku> {

  private final Sku sku;
  private int available;

  public Stock(Sku sku, int available) {
    if (sku == null) {
      // The SKU is this aggregate's identity: a null one would flow into equals/hashCode and the
      // repository's key instead of failing here, at the door.
      throw new DomainException("a stock row needs its SKU");
    }
    if (available < 0) {
      throw new DomainException("available must be >= 0");
    }
    this.sku = sku;
    this.available = available;
  }

  /**
   * Reconstitute a stored stock row. For persistence adapters only.
   *
   * @param version the row's optimistic-lock version, which the repository puts back in the {@code
   *     WHERE} clause when it saves. This is what stops two concurrent reservations of one SKU from
   *     each passing {@link #reserve} on the same snapshot and overselling it.
   */
  public static Stock reconstitute(Sku sku, int available, long version) {
    Stock stock = new Stock(sku, available);
    stock.restoreVersion(version);
    return stock;
  }

  /** Reserve the given quantity, guarding against reserving more than is available. */
  public void reserve(int quantity) {
    if (quantity <= 0) {
      throw new DomainException("quantity must be > 0");
    }
    if (quantity > available) {
      // A single-condition guard, so it stays a coded throw — not worth upgrading to
      // an Invariant (design-00003 §4.5). It carries a stable code so a failed
      // reservation surfaces a machine identity even though inventory has no HTTP edge.
      throw new DomainException(
          InventoryErrorCode.INSUFFICIENT_STOCK, "insufficient stock for " + sku.value());
    }
    this.available -= quantity;
  }

  /**
   * Return a previously reserved quantity to the available pool (the compensation for reserve).
   *
   * <p><strong>Deliberately unbounded here.</strong> This aggregate cannot check "only what was
   * reserved comes back": that would need a {@code reserved} counter, which couples every {@code
   * Stock} write to the reservation concept and — worse — is wrong under partial releases across
   * many reservations. The invariant's real home is the {@link
   * com.example.inventory.domain.reservation.Reservation} aggregate (idempotent, keyed by order,
   * releases exactly what it holds and only once) with {@code ReleaseStockHandler} walking its
   * lines. The trade-off is honest: an in-context bug that calls {@code release} outside that path
   * can inflate availability, and this aggregate will not catch it — the guard was placed where the
   * knowledge lives, not where the mutation happens.
   */
  public void release(int quantity) {
    if (quantity <= 0) {
      throw new DomainException("quantity must be > 0");
    }
    this.available += quantity;
  }

  public int available() {
    return available;
  }

  @Override
  @Identity
  public Sku id() {
    return sku;
  }
}
