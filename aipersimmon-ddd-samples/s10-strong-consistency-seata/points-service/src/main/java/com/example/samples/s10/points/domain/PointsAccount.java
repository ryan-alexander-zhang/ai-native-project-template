package com.example.samples.s10.points.domain;

import com.aipersimmon.ddd.core.annotation.AggregateRoot;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;
import java.util.Optional;

/**
 * A customer's points, in the two shapes a distributed transaction can ask for them.
 *
 * <p><strong>{@link #award} is the AT shape.</strong> One method, one write, and the framework doing the
 * work: Seata's data-source proxy has already captured the row's before-image, so if the global
 * transaction fails, the row goes back and this aggregate never learns that anything happened. Nothing in
 * this method is aware it is a participant, which is the whole appeal of AT.
 *
 * <p><strong>{@link #reserve} / {@link #confirmReservation} / {@link #cancelReservation} are the TCC
 * shape.</strong> Three methods, and the reservation is a fact the model holds: points are promised
 * ({@code frozen}) before they are real ({@code awarded}). That costs a column and two more methods, and
 * it buys the thing AT cannot give — the row's local transaction ends at Try, so nobody waits on it, and
 * "promised" is a state the business can see and reason about instead of a lock nobody can inspect.
 *
 * <p>Which is the DDD observation this sample exists to make: <em>Try/Confirm/Cancel is not plumbing
 * bolted onto an aggregate; it is the aggregate admitting that "reserved" is part of its language.</em>
 * If the business has no word for the intermediate state, TCC is being used to fake one, and AT is the
 * honest choice. If it does have a word — held, frozen, pending, authorised — then that state was always
 * missing from the model and TCC merely forces the issue.
 *
 * <p>Every operation is keyed on a caller-supplied reference and reports an outcome instead of throwing,
 * because each of them can and will be delivered more than once: Seata retries Confirm and Cancel until
 * the coordinator is satisfied.
 */
@AggregateRoot
public final class PointsAccount extends AbstractAggregateRoot<PointsAccountId> {

  private final PointsAccountId id;

  /**
   * The one ledger entry this load is about, if it exists.
   *
   * <p>Not the whole ledger. An aggregate is loaded to answer one question, and the question here is
   * always "what has already happened to <em>this</em> reference" — loading every entry a customer has
   * ever earned would make the write cost grow with the customer's lifetime for no gain. S17 is where
   * that mapping choice belongs; the invariant it protects is only ever about one reference.
   */
  private Entry entry;

  private int awarded;
  private int frozen;

  private PointsAccount(PointsAccountId id, int awarded, int frozen, Entry entry) {
    this.id = id;
    this.awarded = awarded;
    this.frozen = frozen;
    this.entry = entry;
  }

  public static PointsAccount reconstitute(
      PointsAccountId id, int awarded, int frozen, Entry entry, long version) {
    PointsAccount account = new PointsAccount(id, awarded, frozen, entry);
    account.restoreVersion(version);
    return account;
  }

  /**
   * Award points outright — the AT participant's single operation.
   *
   * @return {@link AwardOutcome#ALREADY_AWARDED} when this reference was already settled, which is what
   *     a retried request looks like from here.
   */
  public AwardOutcome award(String reference, int points) {
    requirePositive(points);
    if (entry != null) {
      return entry.state() == EntryState.AWARDED
          ? AwardOutcome.ALREADY_AWARDED
          : AwardOutcome.REFERENCE_IN_USE;
    }
    awarded += points;
    entry = new Entry(reference, points, EntryState.AWARDED);
    return AwardOutcome.AWARDED;
  }

  /**
   * TCC Try: promise the points without making them real yet.
   *
   * <p>The refusal that matters is the third one. A Cancel can arrive <em>before</em> its Try — the
   * coordinator timed the branch out while the Try request was still in flight — and if Try then went
   * ahead it would leave points frozen forever with no Confirm or Cancel ever coming for them. Seata
   * calls this suspension; the fix is that a cancelled reference can never afterwards be reserved. It
   * works here only because Cancel writes a CANCELLED entry even when it had nothing to cancel.
   */
  public ReserveOutcome reserve(String reference, int points) {
    requirePositive(points);
    if (entry != null) {
      return switch (entry.state()) {
        case RESERVED -> ReserveOutcome.ALREADY_RESERVED;
        case AWARDED -> ReserveOutcome.ALREADY_SETTLED;
        case CANCELLED -> ReserveOutcome.CANCELLED_BEFORE_RESERVED;
      };
    }
    frozen += points;
    entry = new Entry(reference, points, EntryState.RESERVED);
    return ReserveOutcome.RESERVED;
  }

  /** TCC Confirm: the promise becomes real. Idempotent, because Seata retries until it is told yes. */
  public SettleOutcome confirmReservation(String reference) {
    if (entry == null) {
      // Confirm for a Try that is not here. Never legitimate: the coordinator only confirms branches it
      // saw registered, so this is a bug or a lost write, and guessing would invent points.
      return SettleOutcome.NOTHING_TO_SETTLE;
    }
    return switch (entry.state()) {
      case RESERVED -> {
        frozen -= entry.points();
        awarded += entry.points();
        entry = new Entry(reference, entry.points(), EntryState.AWARDED);
        yield SettleOutcome.SETTLED;
      }
      case AWARDED -> SettleOutcome.ALREADY_SETTLED;
      case CANCELLED -> SettleOutcome.ALREADY_CANCELLED;
    };
  }

  /**
   * TCC Cancel: give the promise back.
   *
   * <p>Two properties, both non-negotiable and both easy to omit. It must tolerate having nothing to
   * cancel — Seata rolls back branches whose Try never ran (an <em>empty rollback</em>) — and it must
   * leave a mark when it does, so a Try arriving afterwards can be refused. The second is why this
   * writes an entry in the {@code null} case rather than returning quietly.
   */
  public SettleOutcome cancelReservation(String reference, int pointsIfUnknown) {
    if (entry == null) {
      entry = new Entry(reference, pointsIfUnknown, EntryState.CANCELLED);
      return SettleOutcome.NOTHING_TO_SETTLE;
    }
    return switch (entry.state()) {
      case RESERVED -> {
        frozen -= entry.points();
        entry = new Entry(reference, entry.points(), EntryState.CANCELLED);
        yield SettleOutcome.SETTLED;
      }
      case CANCELLED -> SettleOutcome.ALREADY_CANCELLED;
      case AWARDED -> SettleOutcome.ALREADY_SETTLED;
    };
  }

  private static void requirePositive(int points) {
    if (points <= 0) {
      throw new IllegalArgumentException("points must be positive: " + points);
    }
  }

  @Override
  public PointsAccountId id() {
    return id;
  }

  public int awarded() {
    return awarded;
  }

  public int frozen() {
    return frozen;
  }

  public Optional<Entry> entry() {
    return Optional.ofNullable(entry);
  }

  /** One movement, named by the reference the caller chose. */
  public record Entry(String reference, int points, EntryState state) {}

  /** Where a reference stands. */
  public enum EntryState {
    RESERVED,
    AWARDED,
    CANCELLED
  }
}
