package com.example.samples.s25.refunds.domain;

import com.aipersimmon.ddd.core.annotation.AggregateRoot;
import com.aipersimmon.ddd.core.exception.DomainException;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;
import java.util.Optional;
import java.util.UUID;

/**
 * The first aggregate out of the monolith — over a table the library did not design.
 *
 * <p>What was gained by extracting it is exactly one thing, and it is worth stating plainly because it is easy to
 * oversell: <strong>the rules are now in a place where they can be found and refused.</strong> The monolith had all
 * of them except one, spread across an {@code if}, a comparison against an unlocked read, and a {@code WHERE}
 * clause. It had no way to refuse a second approval — it turned that into a silent no-op — and no check at all on a
 * second open refund.
 *
 * <p>What was <em>not</em> gained: the table is the same table, the rows are the same rows, the id is the same
 * {@code bigint}, and the foreign key into {@code legacy_orders} is still there. An extraction that changed those
 * as well would be three migrations at once, with the old writer still running.
 *
 * <h2>The order is an argument, not a reference</h2>
 *
 * <p>A refund is about an order, and this class holds {@code orderId} as a plain {@code long} and nothing else. The
 * two facts it needs to decide anything — is the order cancelled, what is its total — arrive as arguments to
 * {@link #raise}, read by the application through the ACL. The aggregate never calls out.
 *
 * <p>That is not tidiness. The order lives in the monolith, and a model that could reach into the monolith would be
 * a model whose invariants depend on legacy SQL — which is the position the extraction exists to escape. Checked by
 * {@code ArchitectureTest.thenewDomainCannotSeeTheLegacy}.
 *
 * <h2>Why {@code publicId} is here at all</h2>
 *
 * <p>Because the identity handed outward must not be the database's counter, and every refund must have one. A row
 * created by the old path gets one from a {@code DEFAULT}; a row created by this path gets one because {@link #raise}
 * will not build a refund without being handed one. Both are covered, which is what makes the external contract safe
 * to publish during the overlap rather than after it.
 *
 * <p>Handed in rather than minted here. It used to be a {@code UUID.randomUUID()} inside the factory, which made
 * {@code raise} return a different aggregate on every call with the same arguments — untestable at a fixed value, and
 * a v4 where the rest of this codebase mints time-ordered v7 through {@code IdGenerator}. Requiring it as an argument
 * keeps the guarantee (there is no way to raise a refund without one) and moves the minting to the layer that is
 * allowed to have a source of new values. Refused at build time by {@code
 * DeterminismRules.domainShouldNotUseAmbientTimeOrRandomness}.
 */
@AggregateRoot
public final class Refund extends AbstractAggregateRoot<RefundId> {

  private final RefundId id;
  private final long orderId;
  private final long amountCents;
  private final String reason;
  private final UUID publicId;

  private RefundState state;
  private String approvedBy;

  private Refund(
      RefundId id,
      long orderId,
      long amountCents,
      String reason,
      UUID publicId,
      RefundState state,
      String approvedBy) {
    this.id = id;
    this.orderId = orderId;
    this.amountCents = amountCents;
    this.reason = reason;
    this.publicId = publicId;
    this.state = state;
    this.approvedBy = approvedBy;
  }

  /**
   * Raise one, with the order's facts supplied.
   *
   * <p>Three refusals, and the third is the one the monolith did not have. {@code openAlready} is a tally handed in
   * by the application, the same arrangement S27 and S28 use: the aggregate decides, and what it decides on is a
   * count somebody else was in a position to take.
   *
   * @param id the identity the caller has already reserved from the table's sequence — see {@code RefundIds} for
   *     why it has to be reserved rather than assigned by the insert
   * @param publicId the outward identity, minted by the caller from {@code IdGenerator}
   * @param orderCancelled whether the order is cancelled, read through the ACL
   * @param orderTotalCents the order's total, read through the ACL
   * @param openAlready whether this order already has an open refund
   */
  public static Refund raise(
      RefundId id,
      long orderId,
      long amountCents,
      String reason,
      UUID publicId,
      boolean orderCancelled,
      long orderTotalCents,
      boolean openAlready) {
    if (publicId == null) {
      throw new IllegalArgumentException("a refund needs an outward identity");
    }
    if (amountCents <= 0) {
      throw new IllegalArgumentException("a refund must be for a positive amount");
    }
    if (orderCancelled) {
      throw new DomainException(
          RefundErrorCode.ORDER_IS_CANCELLED, "order " + orderId + " is cancelled");
    }
    if (amountCents > orderTotalCents) {
      throw new DomainException(
          RefundErrorCode.EXCEEDS_ORDER_TOTAL,
          "a refund of " + amountCents + " exceeds order " + orderId + "'s total of " + orderTotalCents);
    }
    if (openAlready) {
      throw new DomainException(
          RefundErrorCode.ALREADY_OPEN,
          "order " + orderId + " already has an open refund; the monolith never checked this");
    }
    return new Refund(id, orderId, amountCents, reason, publicId, RefundState.OPEN, null);
  }

  public static Refund reconstitute(
      RefundId id,
      long orderId,
      long amountCents,
      String reason,
      UUID publicId,
      String state,
      String approvedBy,
      long version) {
    Refund refund =
        new Refund(
            id,
            orderId,
            amountCents,
            reason,
            publicId,
            RefundState.valueOf(state),
            approvedBy);
    refund.restoreVersion(version);
    return refund;
  }

  /**
   * Approve it.
   *
   * <p>A refusal where the monolith had a no-op. Which is the whole difference the extraction bought on this
   * method: two approvals racing used to produce one update and one silent nothing, and the caller of the nothing
   * was told it had succeeded.
   */
  public void approve(String approver) {
    if (approver == null || approver.isBlank()) {
      throw new IllegalArgumentException("an approval needs an approver");
    }
    requireOpen();
    this.state = RefundState.APPROVED;
    this.approvedBy = approver;
  }

  public void reject(String reason) {
    requireOpen();
    this.state = RefundState.REJECTED;
    this.approvedBy = null;
  }

  private void requireOpen() {
    if (state.isClosed()) {
      throw new DomainException(
          RefundErrorCode.ALREADY_CLOSED,
          "refund " + id + " is already " + state + "; the monolith would have said nothing");
    }
  }

  @Override
  public RefundId id() {
    return id;
  }

  public long orderId() {
    return orderId;
  }

  public long amountCents() {
    return amountCents;
  }

  public Optional<String> reason() {
    return Optional.ofNullable(reason);
  }

  public UUID publicId() {
    return publicId;
  }

  public RefundState state() {
    return state;
  }

  public Optional<String> approvedBy() {
    return Optional.ofNullable(approvedBy);
  }
}
