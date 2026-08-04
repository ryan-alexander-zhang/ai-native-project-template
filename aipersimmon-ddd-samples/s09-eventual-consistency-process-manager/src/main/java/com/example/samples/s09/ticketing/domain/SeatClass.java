package com.example.samples.s09.ticketing.domain;

import com.aipersimmon.ddd.core.annotation.AggregateRoot;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One class of seats, with its remaining count and the holds taken against it.
 *
 * <p>The counter and the holds are one aggregate on purpose: "there are two left" and "these are the
 * two orders holding them" are the same fact seen twice, and splitting them across two transactions is
 * how a system ends up selling a seat it had already promised (S8's subject, from the other side).
 *
 * <p><strong>A released hold is kept, not deleted.</strong> That single line of modelling is the
 * smallest illustration of what a compensation is: the seat comes back, and the record that it was once
 * held — and when it was let go — remains. A delete would leave the counter correct and the history a
 * lie.
 */
@AggregateRoot
public final class SeatClass extends AbstractAggregateRoot<SeatClassId> {

  private final SeatClassId id;
  private final Map<String, Hold> holds;

  private int available;

  private SeatClass(SeatClassId id, int available, Map<String, Hold> holds) {
    this.id = id;
    this.available = available;
    this.holds = new LinkedHashMap<>(holds);
  }

  public static SeatClass reconstitute(
      SeatClassId id, int available, List<Hold> holds, long version) {
    Map<String, Hold> byOrder = new LinkedHashMap<>();
    holds.forEach(hold -> byOrder.put(hold.orderId(), hold));
    SeatClass seatClass = new SeatClass(id, available, byOrder);
    seatClass.restoreVersion(version);
    return seatClass;
  }

  /**
   * Take a seat for this order.
   *
   * <p>Three outcomes rather than a boolean and an exception, because all three are ordinary:
   * {@link HoldOutcome#SOLD_OUT} is a business answer the flow has to compensate for, and
   * {@link HoldOutcome#ALREADY_HELD} is what a redelivered effect looks like. Only the third is news.
   */
  public HoldOutcome hold(String orderId, Instant now) {
    Hold existing = holds.get(orderId);
    if (existing != null && !existing.released()) {
      return HoldOutcome.ALREADY_HELD;
    }
    if (available <= 0) {
      return HoldOutcome.SOLD_OUT;
    }
    available--;
    holds.put(orderId, new Hold(orderId, now, null));
    return HoldOutcome.HELD;
  }

  /**
   * Give the seat back.
   *
   * @return false when there was nothing held — a redelivered release, or a compensation for a step
   *     that never succeeded. Both are silence, not failure.
   */
  public boolean release(String orderId, Instant now) {
    Hold existing = holds.get(orderId);
    if (existing == null || existing.released()) {
      return false;
    }
    holds.put(orderId, new Hold(orderId, existing.heldAt(), now));
    available++;
    return true;
  }

  @Override
  public SeatClassId id() {
    return id;
  }

  public int available() {
    return available;
  }

  public List<Hold> holds() {
    return List.copyOf(holds.values());
  }

  /** One order's claim on a seat, and when it ended. */
  public record Hold(String orderId, Instant heldAt, Instant releasedAt) {

    public boolean released() {
      return releasedAt != null;
    }
  }
}
