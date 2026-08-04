package com.example.samples.s09.ticketing.domain;

import com.aipersimmon.ddd.core.annotation.AggregateRoot;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A prepaid balance and its ledger — the sample's central exhibit for <strong>compensation is not a
 * rollback</strong>.
 *
 * <p>A refund here is not the removal of a debit. It is {@link #credit}: a second entry, with its own
 * reference, its own sign and its own reason, and both entries stay on the statement forever. That is
 * not bookkeeping pedantry, it is the whole semantic difference:
 *
 * <ul>
 *   <li>a rollback restores a state and leaves no trace that anything happened;
 *   <li>a compensation is a <em>new business action</em> whose meaning is "make good the earlier one",
 *       and it is visible, auditable, and may not even be a mirror image — a real refund can carry a
 *       fee, take days, or be refused.
 * </ul>
 *
 * <p>Both operations are idempotent by {@code reference}, because the coordinator delivers at-least-once
 * and a repeated debit is somebody's money. The reference is derived from the flow, not minted here, so
 * it is identical on every redelivery.
 */
@AggregateRoot
public final class Wallet extends AbstractAggregateRoot<WalletId> {

  private final WalletId id;
  private final Map<String, Entry> entries;

  private long balanceMinor;

  private Wallet(WalletId id, long balanceMinor, Map<String, Entry> entries) {
    this.id = id;
    this.balanceMinor = balanceMinor;
    this.entries = new LinkedHashMap<>(entries);
  }

  public static Wallet reconstitute(
      WalletId id, long balanceMinor, List<Entry> entries, long version) {
    Map<String, Entry> byReference = new LinkedHashMap<>();
    entries.forEach(entry -> byReference.put(entry.reference(), entry));
    Wallet wallet = new Wallet(id, balanceMinor, byReference);
    wallet.restoreVersion(version);
    return wallet;
  }

  /**
   * Take money out.
   *
   * <p>{@link DebitOutcome#INSUFFICIENT_FUNDS} is a business answer and not an error: the flow's job is
   * to compensate for it, and throwing would turn a decision the coordinator can make into a failed
   * effect it can only retry.
   */
  public DebitOutcome debit(String reference, long amountMinor, String note, Instant now) {
    if (entries.containsKey(reference)) {
      return DebitOutcome.ALREADY_APPLIED;
    }
    if (balanceMinor < amountMinor) {
      return DebitOutcome.INSUFFICIENT_FUNDS;
    }
    balanceMinor -= amountMinor;
    entries.put(reference, new Entry(reference, EntryKind.DEBIT, amountMinor, note, now));
    return DebitOutcome.CHARGED;
  }

  /**
   * Put money back, as its own entry.
   *
   * @return false when this reference was already applied — a redelivered compensation
   */
  public boolean credit(String reference, long amountMinor, String note, Instant now) {
    if (entries.containsKey(reference)) {
      return false;
    }
    balanceMinor += amountMinor;
    entries.put(reference, new Entry(reference, EntryKind.CREDIT, amountMinor, note, now));
    return true;
  }

  @Override
  public WalletId id() {
    return id;
  }

  public long balanceMinor() {
    return balanceMinor;
  }

  public List<Entry> entries() {
    return List.copyOf(new ArrayList<>(entries.values()));
  }

  /** One movement of money, with the reference that makes it repeatable-safe. */
  public record Entry(
      String reference, EntryKind kind, long amountMinor, String note, Instant recordedAt) {}

  /** Which way the money went. */
  public enum EntryKind {
    DEBIT,
    CREDIT
  }
}
